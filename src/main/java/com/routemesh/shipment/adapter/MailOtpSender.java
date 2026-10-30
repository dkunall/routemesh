package com.routemesh.shipment.adapter;

import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shared.mail.Mailer;
import com.routemesh.shipment.application.OtpSender;
import com.routemesh.shipment.domain.Shipment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Mails the code after commit. The code is never logged and never put on Kafka or in the outbox. */
@Component
class MailOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(MailOtpSender.class);

    private final Mailer mailer;
    private final RouteMeshProperties props;

    MailOtpSender(Mailer mailer, RouteMeshProperties props) {
        this.mailer = mailer;
        this.props = props;
    }

    @Override
    public void sendAfterCommit(Shipment s, String code) {
        Runnable send = () -> {
            try {
                mailer.send(List.of(s.recipientEmail()), "Your delivery code for " + s.reference(),
                        """
                        Hi %s,

                        Your parcel %s is out for delivery. Share this code with the driver at the door: %s

                        Track it here: %s/v1/track/%s
                        """.formatted(s.recipientName(), s.reference(), code, props.security().publicBaseUrl(),
                                s.trackingToken()), null);
            } catch (RuntimeException e) {
                log.warn("delivery code mail for shipment {} failed; customer can ask for a resend: {}", s.id(),
                        e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
