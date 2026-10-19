package com.agenticai.core.common.budget;

/**
 * Body of {@code POST /api/budget/top-up/confirm} - the values the gateway's
 * client-side checkout {@code handler} callback returns after a successful
 * payment. For Razorpay these are {@code razorpay_order_id},
 * {@code razorpay_payment_id}, {@code razorpay_signature}. The amount and
 * modality are deliberately NOT taken from the client here - they come from the
 * server-side pending-order record keyed by {@code orderId}, so a client can't
 * inflate the credited amount.
 */
public record BudgetTopUpConfirmRequest(String orderId, String paymentId, String signature) {
}
