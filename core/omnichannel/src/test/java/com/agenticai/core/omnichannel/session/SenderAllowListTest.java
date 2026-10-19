package com.agenticai.core.omnichannel.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SenderAllowListTest {

    @Test
    void emptyListMatchesNobody() {
        assertThat(SenderAllowList.parse(null).isEmpty()).isTrue();
        assertThat(SenderAllowList.parse("  \n ").matches("@a:example.org")).isFalse();
    }

    @Test
    void exactEntriesMatchCaseInsensitivelyAndNothingElse() {
        SenderAllowList list = SenderAllowList.parse("@Alice:example.org, @bob:example.org");
        assertThat(list.matches("@alice:EXAMPLE.org")).isTrue();
        assertThat(list.matches("@bob:example.org")).isTrue();
        assertThat(list.matches("@carol:example.org")).isFalse();
        assertThat(list.matches("@alice:example.org.evil.net")).isFalse();
    }

    @Test
    void domainPatternMatchesTheServerNotLookalikes() {
        SenderAllowList list = SenderAllowList.parse("@*:example.org\n*:corp.net");
        assertThat(list.matches("@anyone:example.org")).isTrue();
        assertThat(list.matches("@x:corp.net")).isTrue();
        assertThat(list.matches("@x:evil.example.org")).isFalse();
        assertThat(list.matches("@x:notexample.org")).isFalse();
    }

    @Test
    void starIsAnExplicitEverybody() {
        assertThat(SenderAllowList.parse("*").matches("@a:b.c")).isTrue();
    }

    @Test
    void blankSenderNeverMatches() {
        assertThat(SenderAllowList.parse("*").matches(" ")).isFalse();
        assertThat(SenderAllowList.parse("*").matches(null)).isFalse();
    }

    @Test
    void entriesBeyondTheCapAreIgnored() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SenderAllowList.MAX_ENTRIES + 5; i++) {
            sb.append("@u").append(i).append(":x.org ");
        }
        SenderAllowList list = SenderAllowList.parse(sb.toString());
        assertThat(list.matches("@u0:x.org")).isTrue();
        assertThat(list.matches("@u" + (SenderAllowList.MAX_ENTRIES + 1) + ":x.org")).isFalse();
    }
}
