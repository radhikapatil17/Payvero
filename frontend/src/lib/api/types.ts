/** Shapes the API actually returns. Kept close to the wire deliberately. */

export type ErrorBody = {
  timestamp: string
  status: number
  /** Stable machine-readable code; prefer this over the message. */
  error: string
  message: string
  fieldErrors: Record<string, string>
}

export type AuthResponse = {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInSeconds: number
}

export type CurrentUser = {
  userId: string
  email: string
  role: 'USER' | 'ADMIN'
  createdAt: string
}

export type Account = {
  id: string
  currency: string
  balanceMinorUnits: number
  status: string
  createdAt: string
}

export type Balance = {
  accountId: string
  currency: string
  derivedBalanceMinorUnits: number
  cachedBalanceMinorUnits: number
  consistent: boolean
}

export type TransferStatus = 'PENDING' | 'SETTLED' | 'FAILED' | 'FLAGGED'

export type Transfer = {
  id: string
  sourceAccountId: string
  destinationAccountId: string
  /** Relative to the caller: DEBIT is money out, CREDIT is money in. */
  direction: 'DEBIT' | 'CREDIT'
  counterpartyLabel: string
  counterpartyAccountId: string
  amountMinorUnits: number
  currency: string
  status: TransferStatus
  failureReason: string | null
  createdAt: string
  settledAt: string | null
}

export type StatementLine = {
  entryId: string
  transferId: string
  direction: 'DEBIT' | 'CREDIT'
  amountMinorUnits: number
  balanceAfterMinorUnits: number
  currency: string
  occurredAt: string
}

export type Statement = {
  id: string
  accountId: string
  period: string
  openingBalanceMinorUnits: number
  closingBalanceMinorUnits: number
  netMovementMinorUnits: number
  entryCount: number
  lineItems: StatementLine[]
  generatedAt: string
}

export type FraudFlag = {
  id: string
  transferId: string
  rule: 'VELOCITY_COUNT' | 'VELOCITY_AMOUNT'
  status: 'OPEN' | 'CLEARED' | 'CONFIRMED'
  details: {
    rule: string
    windowSeconds: number
    observedTransferCount: number
    observedAmountMinorUnits: number
    maxTransfersPerWindow: number
    maxAmountPerWindow: number
  }
  reviewedBy: string | null
  reviewedAt: string | null
  createdAt: string
}

export type AuditLogEntry = {
  id: string
  /** The originating outbox row. Unique, which is what makes redelivery harmless. */
  eventId: string
  eventType: string
  aggregateType: string
  aggregateId: string
  /** Resolved to an email by the server. Null when no person acted. */
  actor: string | null
  payload: Record<string, unknown>
  kafkaTopic: string | null
  kafkaPartition: number | null
  kafkaOffset: number | null
  /** Processing time: when the consumer wrote the row, not when the event happened. */
  recordedAt: string
}

/**
 * The stable PagedModel shape, not the legacy PageImpl one: pagination lives
 * under `page`, and none of Spring's internals appear.
 */
export type Paged<T> = {
  content: T[]
  page: {
    size: number
    number: number
    totalElements: number
    totalPages: number
  }
}
