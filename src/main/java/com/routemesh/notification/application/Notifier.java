package com.routemesh.notification.application;

import com.routemesh.outbox.application.EventEnvelope;
import com.routemesh.outbox.application.ProcessedEvents;
import com.routemesh.shared.config.RouteMeshProperties;
import com.routemesh.shared.mail.Mailer;
import com.routemesh.shipment.application.ShipmentEvents;
import com.routemesh.shipment.application.ShipmentStore;
import com.routemesh.shipment.domain.Shipment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Customer and ops mail from shipment events. Deduplicated per event id in the same transaction (a mail sent just
 * before a crash may still go twice; SMTP is not transactional). The delivery code is not mailed from here: it
 * never enters an event, see MailOtpSender.
 */
@Service
public class Notifier {

    static final String CONSUMER = "notifier";

    private final ProcessedEvents processed;
    private final ShipmentStore shipments;
    private final Mailer mailer;
    private final RouteMeshProperties props;

    public Notifier(ProcessedEvents processed, ShipmentStore shipments, Mailer mailer, RouteMeshProperties props) {
        this.processed = processed;
        this.shipments = shipments;
        this.mailer = mailer;
        this.props = props;
    }

    @Transactional
    public boolean handle(EventEnvelope e) {
        if (!processed.markProcessed(CONSUMER, e.eventId())) {
            return false;
        }
        var s = shipments.find(UUID.fromString((String) e.data().get("shipmentId"))).orElse(null);
        if (s == null) {
            return false;
        }
        String status = (String) e.data().get("status");
        String ops = props.mail().opsAddress();
        switch (e.type()) {
            case ShipmentEvents.CREATED -> customer(s, "Your parcel " + s.reference() + " is booked",
                    "It is due " + slot(s) + ".\nTrack it or pick another day here: " + link(s));
            case ShipmentEvents.RESCHEDULED -> customer(s, "New delivery slot for " + s.reference(),
                    "We will come " + slot(s) + ".\n" + link(s));
            case ShipmentEvents.ALERT -> {
                mailer.send(List.of(ops), "[routemesh] " + e.data().get("kind") + " " + s.reference(),
                        "Shipment " + s.id() + " on route " + e.data().get("routeId") + " is predicted at "
                                + e.data().get("predictedEta") + ", window ends " + s.windowEnd() + ".\n", null);
                return true;
            }
            case ShipmentEvents.STATUS_CHANGED -> {
                switch (status) {
                    case "FAILED_ATTEMPT" -> {
                        if (s.status().plannable()) {
                            customer(s, "We missed you (" + s.reference() + ")",
                                    "We could not complete it today. We will try again " + slot(s)
                                            + ".\nIf that does not suit you, pick another day: " + link(s));
                        } else {
                            customer(s, "We could not deliver " + s.reference(), "After " + s.attempts()
                                    + (s.kind() == com.routemesh.shipment.domain.ShipmentKind.DELIVERY
                                    ? " attempts the parcel is going back to the sender."
                                    : " attempts we have stopped trying to collect it. Please contact the shop."));
                        }
                    }
                    case "DELIVERED" -> customer(s, "Delivered: " + s.reference(), "Your parcel was delivered.");
                    case "RTO" -> mailer.send(List.of(ops), "[routemesh] RTO " + s.reference(),
                            "Shipment " + s.id() + " failed " + s.attempts() + " attempts and is going back to the hub.\n",
                            null);
                    default -> {
                        return false;
                    }
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void customer(Shipment s, String subject, String body) {
        mailer.send(List.of(s.recipientEmail()), subject, "Hello " + s.recipientName() + ",\n\n" + body + "\n\nRouteMesh\n",
                null);
    }

    private String slot(Shipment s) {
        var zone = props.planning().zone();
        var day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
        var hm = DateTimeFormatter.ofPattern("HH:mm");
        return "on " + s.serviceDate().format(day) + " between " + s.windowStart().atZone(zone).format(hm) + " and "
                + s.windowEnd().atZone(zone).format(hm);
    }

    private String link(Shipment s) {
        return props.security().publicBaseUrl() + "/v1/track/" + s.trackingToken();
    }
}
