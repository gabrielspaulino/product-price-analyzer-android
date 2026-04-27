package com.promo.tracker.billing;

import java.io.IOException;

public interface BillingProvider {
    String createCheckoutUrl(String userToken, String userId) throws IOException;
    String createPortalUrl(String userToken, String userId) throws IOException;
}
