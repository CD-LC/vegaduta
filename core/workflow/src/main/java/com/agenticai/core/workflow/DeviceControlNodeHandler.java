package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.devices.DeviceCommandService;
import com.agenticai.core.devices.DeviceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** "device_control": enqueues a fire-and-forget command for a device. */
@Component
class DeviceControlNodeHandler implements NodeHandler {

    private final DeviceCommandService deviceCommandService;
    DeviceControlNodeHandler(DeviceCommandService deviceCommandService) {
        this.deviceCommandService = deviceCommandService;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("device_control");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeDeviceControlNode(inv.node(), inv.input(), inv.runId());
    }

    private NodeExecutionResult executeDeviceControlNode(
            WorkflowNodeDto node, String input, String runId) {
        Object deviceIdRaw = node.config().get("deviceId");
        if (deviceIdRaw == null || deviceIdRaw.toString().isBlank()) {
            return NodeExecutionResult.skipped(
                    input, "No device selected for this Device Control node.");
        }
        String commandType = stringConfig(node, "commandType", null);
        if (commandType == null) {
            return NodeExecutionResult.skipped(
                    input, "No command type configured for this Device Control node.");
        }
        String payload = stringConfig(node, "payload", input);
        List<String> logLines = new ArrayList<>();
        try {
            UUID deviceId = UUID.fromString(deviceIdRaw.toString());
            var command =
                    deviceCommandService.enqueue(
                            deviceId, commandType, payload, UUID.fromString(runId));
            logLines.add(
                    "Queued command "
                            + command.getId()
                            + " ("
                            + commandType
                            + ") for device "
                            + deviceId
                            + ".");
            logLines.add(
                    "Fire-and-forget: the device must be online and polling to receive it. "
                            + "This run does NOT wait for the device to act on it - check the device's command history for the result.");
            return NodeExecutionResult.ok(
                    input,
                    "Command queued for device (not executed within this run - see log).",
                    null,
                    logLines);
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.skipped(input, "Invalid device id: " + deviceIdRaw);
        } catch (DeviceException e) {
            logLines.add("Device Control failed: " + e.getMessage());
            return NodeExecutionResult.skipped(input, e.getMessage(), logLines);
        }
    }
}
