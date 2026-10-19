package io.vegaduta.agent.api.approval;

import io.vegaduta.agent.api.GovernanceException;
import java.util.UUID;

public class ApprovalNotFoundException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  public ApprovalNotFoundException(UUID id) {
    super("Approval request " + id + " not found");
  }
}
