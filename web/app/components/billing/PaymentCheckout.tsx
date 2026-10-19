'use client';

import { useCallback, useState } from 'react';
import { Button } from '@/app/components/ui/button';
import { api } from '@/app/lib/api';
import { openOrderCheckout } from '@/app/lib/payments/checkout';
import type { OrderHandoff, OrderPaymentResult } from '@/app/lib/payments/types';
import { toast as toastBus } from '@/app/lib/toastBus';

export type PaymentSuccessData = OrderPaymentResult;

interface PaymentCheckoutProps {
  /** Amount in the currency's smallest unit (100 = ₹1 / $1). */
  amount: number;
  currency: 'INR' | 'USD';
  receipt?: string;
  description?: string;
  /** Optional gateway name; blank = the deployment's default gateway. */
  gateway?: string;
  onSuccess?: (paymentData: PaymentSuccessData) => void;
  onError?: (error: string) => void;
  disabled?: boolean;
  className?: string;
}

/**
 * One-off payment button that works for ANY gateway core has configured.
 *
 * 1. POST /api/billing/checkout/create-order (through apiFetch: API origin + auth token) - core answers with the
 *    gateway, an order id, its public key, and whether to open a widget or follow a hosted-page URL.
 * 2. The shared checkout layer opens the widget (driver chosen by gateway name) or redirects.
 * 3. A widget hands back ids + a signature; POST /api/billing/checkout/verify-payment checks it server-side.
 * 4. onSuccess fires only after that verification passed.
 *
 * A hosted-page gateway redirects away in step 2 and is confirmed by its webhook, so onSuccess does not fire here.
 */
export function PaymentCheckout({
  amount,
  currency,
  receipt,
  description = 'Payment',
  gateway,
  onSuccess,
  onError,
  disabled = false,
  className = '',
}: PaymentCheckoutProps) {
  const [loading, setLoading] = useState(false);

  const handlePayment = useCallback(async () => {
    if (loading) return;
    setLoading(true);
    const fail = (msg: string) => {
      toastBus.error(msg);
      onError?.(msg);
    };
    try {
      const order = (await api.createCheckoutOrder({
        amountInSubunits: amount,
        currency,
        receipt,
        gateway,
      })) as OrderHandoff;

      const outcome = await openOrderCheckout(order, {
        description,
        onPaid: (result) => {
          void (async () => {
            try {
              await api.verifyCheckoutPayment(result);
              toastBus.success('Payment completed successfully');
              onSuccess?.(result);
            } catch (error) {
              fail(error instanceof Error ? error.message : 'Payment verification failed');
            } finally {
              setLoading(false);
            }
          })();
        },
        onDismiss: () => {
          setLoading(false);
          toastBus.info('Payment cancelled');
          onError?.('Payment cancelled by user');
        },
      });
      if (outcome === 'unavailable') {
        setLoading(false);
        fail('The payment window could not be opened. Check your connection and try again.');
      }
      // "opened": the widget callbacks above end the loading state; "redirecting": the page is leaving.
    } catch (error) {
      setLoading(false);
      fail(error instanceof Error ? error.message : 'An error occurred');
    }
  }, [amount, currency, receipt, description, gateway, loading, onSuccess, onError]);

  return (
    <Button onClick={handlePayment} disabled={disabled || loading} className={className}>
      {loading ? 'Processing...' : `Pay ${currency === 'INR' ? '₹' : '$'}${(amount / 100).toFixed(2)}`}
    </Button>
  );
}
