package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The conservative "this click cannot be undone" heuristic and the label check behind
 * browser_confirm_click.
 */
class BrowserIrreversibleClickTest {

    @Test
    void flagsPurchaseSubmitDeleteSendLikeElements() {
        String[][] flagged = {
            {"button", "Buy now"},
            {"button", "BUY NOW"},
            {"button", "Pay $19.99"},
            {"button", "Payment"},
            {"button", "Place order"},
            {"button", "Confirm purchase"},
            {"button", "Confirmation"},
            {"button", "Submit"},
            {"button", "Submit application"},
            {"button", "Delete account"},
            {"button", "Deleting..."},
            {"button", "Send message"},
            {"button", "Transfer funds"},
            {"button", "Checkout"},
            {"button", "Check out"},
            {"button", "Subscribe"},
            {"button", "Publish"},
            {"button", "Authorize"},
            {"button", "Authorise"},
            {"clickable", "Donate"},
            {"menuitem", "Delete"},
            {"link", "Buy now"},
            {"link", "Pay"},
            {"link", "Confirm your email"},
            {"link", "Delete my data"}
        };
        for (String[] pair : flagged) {
            assertThat(BrowserIrreversibleClick.isIrreversible(pair[0], pair[1]))
                    .as(pair[0] + " '" + pair[1] + "'")
                    .isTrue();
        }
    }

    @Test
    void leavesOrdinaryInteractionsAlone() {
        String[][] ordinary = {
            {"button", "Search"},
            {"button", "Next"},
            {"button", "Continue"},
            {"button", "Add to cart"},
            {"button", "Sign in"},
            {"button", "Close"},
            {"button", "Open menu"},
            {"button", "Border"},
            {"link", "Orders"},
            {"link", "Send feedback"},
            {"link", "Subscribe to our newsletter"},
            {"textbox", "Submit"},
            {"checkbox", "Confirm"},
            {"combobox", "Delete"},
            {"button", ""}
        };
        for (String[] pair : ordinary) {
            assertThat(BrowserIrreversibleClick.isIrreversible(pair[0], pair[1]))
                    .as(pair[0] + " '" + pair[1] + "'")
                    .isFalse();
        }
    }

    @Test
    void nullAndBlankInputsAreNotFlagged() {
        assertThat(BrowserIrreversibleClick.isIrreversible(null, "Buy")).isFalse();
        assertThat(BrowserIrreversibleClick.isIrreversible("button", null)).isFalse();
        assertThat(BrowserIrreversibleClick.isIrreversible("button", "   ")).isFalse();
    }

    @Test
    void theRefusalNamesTheElementAndPointsAtTheConfirmTool() {
        String note = BrowserIrreversibleClick.refusalNote("[7] button 'Pay now'");
        assertThat(note)
                .contains("[7] button 'Pay now'")
                .contains("browser_confirm_click")
                .contains("NOT clicked");
    }

    @Test
    void labelMatchingToleratesCaseAndSpacingButNotADifferentElement() {
        assertThat(BrowserIrreversibleClick.labelMatches("Pay now", "pay   NOW")).isTrue();
        assertThat(BrowserIrreversibleClick.labelMatches("Pay $19.99 now", "Pay $19.99 now"))
                .isTrue();
        // equality, not containment: a fragment or a misleading superstring is not the element's
        // name
        assertThat(BrowserIrreversibleClick.labelMatches("Pay $19.99 now", "Pay")).isFalse();
        assertThat(BrowserIrreversibleClick.labelMatches("Pay now", "Don't pay now")).isFalse();
        assertThat(BrowserIrreversibleClick.labelMatches("Pay", "P")).isFalse();
        assertThat(BrowserIrreversibleClick.labelMatches("Don’t pay", "Don't pay")).isTrue();
        assertThat(BrowserIrreversibleClick.labelMatches("Pay now", "Delete everything")).isFalse();
        assertThat(BrowserIrreversibleClick.labelMatches("Pay now", "")).isFalse();
        assertThat(BrowserIrreversibleClick.labelMatches("", "Pay now")).isFalse();
        assertThat(BrowserIrreversibleClick.labelMatches(null, null)).isFalse();
    }
}
