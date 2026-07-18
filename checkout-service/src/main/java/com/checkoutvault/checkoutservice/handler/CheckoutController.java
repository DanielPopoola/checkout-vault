package com.checkoutvault.checkoutservice.handler;

import com.checkoutvault.checkoutservice.service.CheckoutResult;
import com.checkoutvault.checkoutservice.service.CheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The request body is passed through to Fraud/Payment unmodified as a
 * raw JSON string — this project's mocks don't inspect payload content
 * (per README scope, "the content of the response is not the point"),
 * so there's no real order schema to validate here.
 */
@RestController
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResult> checkout(
        @RequestBody(required = false) String orderPayload
    ) {
        String body = orderPayload == null ? "" : orderPayload;
        CheckoutResult result = checkoutService.checkout(body);

        HttpStatus status =
            result.status() == CheckoutResult.Status.APPROVED
                ? HttpStatus.OK
                : HttpStatus.UNPROCESSABLE_CONTENT;

        return ResponseEntity.status(status).body(result);
    }
}
