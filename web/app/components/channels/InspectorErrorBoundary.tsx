"use client";

// Keeps a crash inside one channel inspector from taking the whole Channels page down to the full-screen 500: the
// failing inspector shows an inline message with a retry, and the channel list on the left stays usable.
import { Component, type ReactNode } from "react";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";

interface Props {
  /** Changing this (e.g. the selected channel) clears a previous error. */
  resetKey: string;
  children: ReactNode;
  title: string;
  retryLabel: string;
}
interface State {
  error: Error | null;
  key: string;
}

export default class InspectorErrorBoundary extends Component<Props, State> {
  override state: State = { error: null, key: this.props.resetKey };

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error };
  }

  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    return props.resetKey !== state.key ? { error: null, key: props.resetKey } : null;
  }

  override componentDidCatch(error: Error): void {
    // eslint-disable-next-line no-console
    console.error("[channels] inspector crashed:", error);
  }

  override render(): ReactNode {
    if (!this.state.error) return this.props.children;
    return (
      <Alert variant="destructive" data-testid="channels-inspector-crash">
        <AlertDescription className="space-y-2">
          <div className="font-medium">{this.props.title}</div>
          <div className="break-words font-mono text-xs">{this.state.error.message}</div>
          <Button type="button" size="sm" variant="outline" onClick={() => this.setState({ error: null })}>
            {this.props.retryLabel}
          </Button>
        </AlertDescription>
      </Alert>
    );
  }
}
