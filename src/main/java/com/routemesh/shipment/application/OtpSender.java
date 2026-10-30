package com.routemesh.shipment.application;

import com.routemesh.shipment.domain.Shipment;

/**
 * Port: hands the plain code to the recipient. Called inside the transaction; the adapter defers the send to
 * after commit, so a rolled-back change never mails a code. A lost mail is recovered with the resend endpoint.
 */
public interface OtpSender {

    void sendAfterCommit(Shipment s, String code);
}
