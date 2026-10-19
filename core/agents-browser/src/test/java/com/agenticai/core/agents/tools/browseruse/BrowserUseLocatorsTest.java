package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link BrowserUseLocators}' selector-vs-plain-description resolution (see its
 * javadoc) - the piece that lets {@code browser_click}/ {@code browser_type} accept either a real
 * CSS selector or a natural-language element description from the model. {@link Page}/{@link
 * Locator} are plain interfaces in the Playwright Java API, so this is mockable without ever
 * touching a real browser - no Chromium binary needed for this test class.
 */
class BrowserUseLocatorsTest {

    @Test
    void aCssLikeSelectorGoesStraightToPageLocator() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.locator("#submit")).thenReturn(locator);

        BrowserUseLocators.performWithFallback(page, "#submit", Locator::click);

        verify(locator).click();
        verify(page, never()).getByText(anyString(), any());
    }

    @Test
    void aPlainTextDescriptionWithSpacesGoesStraightToGetByText() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.getByText(anyString(), any())).thenReturn(locator);

        BrowserUseLocators.performWithFallback(page, "the Submit button", Locator::click);

        verify(locator).click();
        verify(page, never()).locator(anyString());
    }

    @Test
    void aCssAttemptThatThrowsFallsBackToGetByText() {
        Page page = mock(Page.class);
        Locator cssLocator = mock(Locator.class);
        Locator textLocator = mock(Locator.class);
        when(page.locator("LoginButton")).thenReturn(cssLocator);
        // No whitespace, selector-safe chars - isSelectorAttempt says "try it as
        // a selector first" even though it's really a coincidentally
        // space-free description.
        org.mockito.Mockito.doThrow(new PlaywrightException("no element")).when(cssLocator).click();
        when(page.getByText(anyString(), any())).thenReturn(textLocator);

        BrowserUseLocators.performWithFallback(page, "LoginButton", Locator::click);

        verify(cssLocator, times(1)).click();
        verify(textLocator, times(1)).click();
    }

    @Test
    void anExplicitEnginePrefixIsPassedThroughVerbatim() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.locator("role=button[name=\"Submit\"]")).thenReturn(locator);

        BrowserUseLocators.performWithFallback(
                page, "role=button[name=\"Submit\"]", Locator::click);

        verify(locator).click();
    }

    @Test
    void blankSelectorThrowsBrowserUseException() {
        Page page = mock(Page.class);
        assertThatThrownBy(() -> BrowserUseLocators.performWithFallback(page, "  ", Locator::click))
                .isInstanceOf(BrowserUseException.class);
        assertThatThrownBy(() -> BrowserUseLocators.performWithFallback(page, null, Locator::click))
                .isInstanceOf(BrowserUseException.class);
    }
}
