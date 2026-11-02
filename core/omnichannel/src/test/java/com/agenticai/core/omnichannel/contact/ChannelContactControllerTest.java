package com.agenticai.core.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.omnichannel.contact.ChannelContactController.ContactRequest;
import com.agenticai.core.omnichannel.contact.ChannelContactController.ContactResponse;
import com.agenticai.core.omnichannel.contact.ChannelContactController.ContactUpdateRequest;
import com.agenticai.core.omnichannel.contact.ChannelContactController.GroupRequest;
import com.agenticai.core.omnichannel.contact.ChannelContactController.GroupResponse;
import com.agenticai.core.omnichannel.contact.ChannelContactController.ImportRequest;
import com.agenticai.core.omnichannel.contact.ChannelContactService.ImportResult;
import com.agenticai.core.omnichannel.contact.ChannelContactService.ImportRow;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Channel parsing, response mapping and the three import formats of {@link
 * ChannelContactController}.
 */
class ChannelContactControllerTest {

    private ChannelContactService service;
    private ChannelSessionRepository sessions;
    private AgentRepository agents;
    private ChannelContactController controller;

    @BeforeEach
    void setUp() {
        service = mock(ChannelContactService.class);
        sessions = mock(ChannelSessionRepository.class);
        agents = mock(AgentRepository.class);
        controller = new ChannelContactController(service, sessions, agents);
        when(service.importRows(any(), any(), any())).thenReturn(new ImportResult(0, 0, List.of()));
    }

    private static ChannelContact contact(UUID sessionId, UUID groupId, UUID agentId) {
        ChannelContact c =
                new ChannelContact(
                        ChannelType.WHATSAPP, "+919812345678", "Asha", groupId, agentId, "IMPORT");
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        if (sessionId != null) {
            c.linkSession(sessionId);
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<ImportRow> importedRows() {
        ArgumentCaptor<List<ImportRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(service).importRows(any(), any(), rows.capture());
        return rows.getValue();
    }

    @Test
    void channelNamesAreCaseInsensitiveAndMustBeKnown() {
        assertThat(ChannelContactController.parseChannel(" whatsapp "))
                .isEqualTo(ChannelType.WHATSAPP);
        assertThatThrownBy(() -> ChannelContactController.parseChannel(" "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> ChannelContactController.parseChannel(null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> ChannelContactController.parseChannel("carrier-pigeon"))
                .hasMessageContaining("Unknown channel");
    }

    @Test
    void listingResolvesTheSessionGroupAndAgentNamesForEachContact() {
        UUID sessionId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        ChannelSession session = new ChannelSession(ChannelType.WHATSAPP, "x", "s", agentId);
        session.approvePairing();
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("Helper");
        ContactGroup group = new ContactGroup("VIP", null, null);
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        when(agents.findById(agentId)).thenReturn(Optional.of(agent));
        when(service.findGroup(groupId)).thenReturn(Optional.of(group));
        when(service.list(ChannelType.WHATSAPP, null, 10, 0L))
                .thenReturn(List.of(contact(sessionId, groupId, agentId)));
        when(service.count(ChannelType.WHATSAPP, null)).thenReturn(1L);

        ResponseEntity<List<ContactResponse>> response = controller.list("whatsapp", null, 10, 0L);

        ContactResponse row = response.getBody().get(0);
        assertThat(row.groupName()).isEqualTo("VIP");
        assertThat(row.agentName()).isEqualTo("Helper");
        assertThat(row.paired()).isTrue();
    }

    @Test
    void aContactWithoutALinkedSessionIsUnpairedAndAnUnknownAgentHasNoName() {
        when(service.findGroup(any())).thenReturn(Optional.empty());
        when(service.list(null, null, null, null))
                .thenReturn(List.of(contact(null, null, UUID.randomUUID())));

        ContactResponse row = controller.list(" ", null, null, null).getBody().get(0);

        assertThat(row.paired()).isFalse();
        assertThat(row.agentName()).isNull();
        assertThat(row.groupName()).isNull();
    }

    @Test
    void createUpdateAndDeleteDelegateToTheService() {
        UUID id = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        when(service.findGroup(any())).thenReturn(Optional.empty());
        when(service.upsert(ChannelType.SLACK, "C1", "Ops", group, null, "IMPORT"))
                .thenReturn(contact(null, group, null));
        when(service.update(id, "New", group, null)).thenReturn(contact(null, group, null));

        assertThat(controller.create(new ContactRequest("slack", "C1", "Ops", group, null)))
                .isNotNull();
        assertThat(controller.update(id, new ContactUpdateRequest("New", group, null))).isNotNull();
        controller.delete(id);

        verify(service).delete(id);
    }

    @Test
    void groupsAreListedCreatedUpdatedAndDeletedWithMemberCounts() {
        UUID agentId = UUID.randomUUID();
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("Helper");
        when(agents.findById(agentId)).thenReturn(Optional.of(agent));
        ContactGroup g = new ContactGroup("VIP", "top customers", agentId);
        ReflectionTestUtils.setField(g, "id", UUID.randomUUID());
        when(service.listGroups()).thenReturn(List.of(g));
        when(service.memberCount(g.getId())).thenReturn(7L);
        when(service.createGroup("VIP", "top customers", agentId)).thenReturn(g);
        when(service.updateGroup(g.getId(), "VIP", "top customers", agentId)).thenReturn(g);

        List<GroupResponse> listed = controller.listGroups(null, null).getBody();
        GroupResponse created =
                controller.createGroup(new GroupRequest("VIP", "top customers", agentId));
        GroupResponse updated =
                controller.updateGroup(
                        g.getId(), new GroupRequest("VIP", "top customers", agentId));
        controller.deleteGroup(g.getId());

        assertThat(listed.get(0).memberCount()).isEqualTo(7);
        assertThat(created.memberCount()).isZero();
        assertThat(created.agentName()).isEqualTo("Helper");
        assertThat(updated.memberCount()).isEqualTo(7);
        verify(service).deleteGroup(g.getId());
    }

    @Test
    void pastedRowsSkipAHeaderStripQuotesAndAcceptAnySeparator() {
        String text =
                "﻿phone,name,group\n\n+911111111111,\"Asha\",VIP\n+912222222222;Ravi\n\t\n\"+913333333333\"\n+914444444444,,\n";

        controller.importRows(new ImportRequest("whatsapp", null, text));

        assertThat(importedRows())
                .containsExactly(
                        new ImportRow("+911111111111", "Asha", "VIP"),
                        new ImportRow("+912222222222", "Ravi", null),
                        new ImportRow("+913333333333", null, null),
                        new ImportRow("+914444444444", null, null));
    }

    @Test
    void nullTextImportsNothingAndTooManyRowsIsRefused() {
        controller.importRows(new ImportRequest("whatsapp", null, null));
        assertThat(importedRows()).isEmpty();

        String big = "+91\n".repeat(5001);
        assertThatThrownBy(() -> controller.importRows(new ImportRequest("whatsapp", null, big)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("5000");
    }

    @Test
    void aCsvUploadIsParsedAsLines() {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "list.CSV", "text/csv", "+911111111111,Asha\n".getBytes());

        controller.importFile(file, "whatsapp", null);

        assertThat(importedRows()).containsExactly(new ImportRow("+911111111111", "Asha", null));
    }

    @Test
    void anEmptyUploadIsRefusedAndAnUnreadableSpreadsheetBecomesA400() {
        assertThatThrownBy(
                        () ->
                                controller.importFile(
                                        new MockMultipartFile(
                                                "file", "a.csv", "text/csv", new byte[0]),
                                        "whatsapp",
                                        null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Choose a");
        MockMultipartFile bad =
                new MockMultipartFile(
                        "file", "a.xlsx", "application/octet-stream", "not a workbook".getBytes());
        assertThatThrownBy(() -> controller.importFile(bad, "whatsapp", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Could not read");
        MockMultipartFile noName =
                new MockMultipartFile("file", null, "text/plain", "+911111111111".getBytes());
        controller.importFile(noName, "whatsapp", UUID.randomUUID());
        verify(service).importRows(eq(ChannelType.WHATSAPP), any(), any());
    }

    @Test
    void aSpreadsheetUploadReadsTextNumericAndFormulaCellsAndSkipsItsHeader() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Phone number");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(919812345678d);
            r1.createCell(1).setCellValue(" Asha ");
            r1.createCell(2).setCellValue("VIP");
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellFormula("\"+91\"&\"2222222222\"");
            r2.createCell(1).setCellValue(12.5);
            sheet.createRow(3).createCell(1).setCellValue("no id");
            Row r4 = sheet.createRow(4);
            r4.createCell(0).setCellValue("+913333333333");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            bytes = out.toByteArray();
        }
        MockMultipartFile file =
                new MockMultipartFile("file", "contacts.xlsx", "application/vnd.ms-excel", bytes);

        controller.importFile(file, "whatsapp", null);

        List<ImportRow> rows = importedRows();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).isEqualTo(new ImportRow("919812345678", "Asha", "VIP"));
        assertThat(rows.get(1).name()).isEqualTo("12.5");
        assertThat(rows.get(2).identifier()).isEqualTo("+913333333333");
    }
}
