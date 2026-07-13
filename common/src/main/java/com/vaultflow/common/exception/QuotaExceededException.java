package com.vaultflow.common.exception;

import org.springframework.http.HttpStatus;

public class QuotaExceededException extends VaultFlowException {
  public QuotaExceededException(String orgId, long quotaBytes) {
    super(
        String.format(
            "Storage quota exceeded for organization %s. Limit: %d bytes", orgId, quotaBytes),
        HttpStatus.PAYMENT_REQUIRED,
        "QUOTA_EXCEEDED");
  }
}
