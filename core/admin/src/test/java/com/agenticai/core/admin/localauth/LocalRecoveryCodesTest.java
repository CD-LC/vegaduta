package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocalRecoveryCodesTest {

    InMemoryLocalCredentialStore store = new InMemoryLocalCredentialStore();
    LocalRecoveryCodes codes = new LocalRecoveryCodes(store, Clock.systemUTC());
    String account = InMemoryLocalCredentialStore.newId();

    @Test
    void aSetIsEightDistinctGroupedCodesFromTheUnambiguousAlphabet() {
        List<String> set = codes.regenerate(account);

        assertThat(set).hasSize(8);
        assertThat(new HashSet<>(set)).hasSize(8);
        for (String code : set) {
            assertThat(code).matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}");
            assertThat(code).doesNotContain("0", "O", "1", "I");
        }
        assertThat(codes.remaining(account)).isEqualTo(8);
    }

    @Test
    void onlyTheHashIsStored() {
        List<String> set = codes.regenerate(account);

        assertThat(store.codes).hasSize(8);
        for (String code : set) {
            String raw = code.replace("-", "");
            assertThat(store.codes).noneMatch(c -> c.hash().contains(raw));
            assertThat(store.codes).anyMatch(c -> c.hash().equals(LocalRecoveryCodes.hash(raw)));
        }
        assertThat(store.codes).allMatch(c -> c.hash().length() == 64);
    }

    @Test
    void aCodeWorksExactlyOnce() {
        String code = codes.regenerate(account).get(0);

        assertThat(codes.consume(code)).isEqualTo(Optional.of(account));
        assertThat(codes.consume(code)).as("second use").isEmpty();
        assertThat(codes.remaining(account)).isEqualTo(7);
    }

    @Test
    void regeneratingMakesEveryOldCodeUseless() {
        List<String> old = codes.regenerate(account);
        List<String> fresh = codes.regenerate(account);

        assertThat(codes.remaining(account)).isEqualTo(8);
        for (String code : old) {
            assertThat(codes.consume(code)).isEmpty();
        }
        assertThat(codes.consume(fresh.get(3))).contains(account);
    }

    @Test
    void dashesSpacesAndCaseDoNotMatter() {
        String code = codes.regenerate(account).get(0);
        String raw = code.replace("-", "");

        assertThat(LocalRecoveryCodes.normalise(code.toLowerCase())).isEqualTo(raw);
        assertThat(LocalRecoveryCodes.normalise("  " + raw.substring(0, 8).toLowerCase() + " / " + raw.substring(8) + " ")).isEqualTo(raw);
        assertThat(codes.consume(code.toLowerCase().replace("-", " "))).contains(account);
    }

    @Test
    void wrongLengthOrJunkIsRefusedWithoutTouchingTheStore() {
        codes.regenerate(account);

        assertThat(codes.consume(null)).isEmpty();
        assertThat(codes.consume("")).isEmpty();
        assertThat(codes.consume("ABCD-EFGH")).isEmpty();
        assertThat(codes.consume("ABCD-EFGH-JKLM-NPQR-STUV")).isEmpty();
        assertThat(codes.consume("AAAA-AAAA-AAAA-AAAA")).as("well formed but not issued").isEmpty();
        assertThat(codes.remaining(account)).isEqualTo(8);
    }

    @Test
    void codesBelongToTheirOwnAccount() {
        String other = InMemoryLocalCredentialStore.newId();
        String mine = codes.regenerate(account).get(0);
        codes.regenerate(other);

        assertThat(codes.consume(mine)).contains(account);
        assertThat(codes.remaining(other)).isEqualTo(8);
    }
}
