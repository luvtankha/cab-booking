package io.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.*;
import org.springframework.http.*;
import org.springframework.mail.javamail.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

record PaymentSetup(String status,String clientSecret) {}

@Service
class Billing {
    final Trips trips;
    final Data data;
    final String secret;
    final String publicKey;
    final String webhook;
    final boolean demo;
    final boolean google;
    final String mailFrom;
    final boolean mailEnabled;
    final ObjectProvider<JavaMailSender> mail;
    final ObjectMapper json;
    Billing(Trips trips,Data data,ObjectMapper json,ObjectProvider<JavaMailSender> mail,Environment env,
            @Value("${app.stripe-secret}") String secret,@Value("${app.stripe-public}") String publicKey,
            @Value("${app.stripe-signing}") String webhook,@Value("${app.demo}") boolean demo,
            @Value("${app.receipt-from}") String mailFrom,@Value("${spring.mail.host}") String mailHost) {
        this.trips=trips;this.data=data;this.secret=secret;this.publicKey=publicKey;this.webhook=webhook;this.demo=demo;
        this.mail=mail;this.mailFrom=mailFrom;this.mailEnabled=!mailHost.isBlank()&&!mailFrom.isBlank();this.json=json;
        this.google=env.acceptsProfiles(Profiles.of("google"));
        if(demo&&env.acceptsProfiles(Profiles.of("render"))) throw new IllegalStateException("Demo payments are restricted to local and test environments.");
    }
    boolean enabled(){return !secret.isBlank()&&!publicKey.isBlank()&&!webhook.isBlank();}
    Map<String,Object> config(){return Map.of("stripe",enabled(),"stripeKey",enabled()?publicKey:"","demo",demo,"google",google,"receiptEmail",mailEnabled);}
    private Ride completed(Account user,String id,boolean lock) {
        Trips.require(user,"RIDER");Ride r=lock?trips.lock(id):trips.find(id);
        if(!r.riderId.equals(user.id())) throw Problems.missing();
        if(r.status!=RideStatus.COMPLETED) throw Problems.conflict("The trip must be completed first.");
        return r;
    }

    @Transactional PaymentSetup initiate(Account user,String id) {
        Ride ride=completed(user,id,true);
        if(!enabled()) throw Problems.of(HttpStatus.SERVICE_UNAVAILABLE,"Stripe has not been configured.");
        Payment current=data.payment(id).orElse(null);
        if(current!=null&&!current.status().equals("PENDING")) return new PaymentSetup(current.status(),null);
        try {
            RequestOptions options=RequestOptions.builder().setApiKey(secret).build();
            PaymentIntent payment;
            if(current!=null) payment=PaymentIntent.retrieve(current.intentId(),options);
            else {
                var input=PaymentIntentCreateParams.builder().setAmount(ride.fareMinor).setCurrency("inr")
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build())
                    .setDescription("Wayfare ride").putMetadata("rideId",ride.id).build();
                payment=PaymentIntent.create(input,RequestOptions.builder().setApiKey(secret).setIdempotencyKey("wayfare-"+ride.id).build());
                data.payment(ride.id,payment.getId(),ride.fareMinor,"PENDING");
            }
            if("succeeded".equals(payment.getStatus())) return new PaymentSetup("PENDING",null);
            return new PaymentSetup("PENDING",payment.getClientSecret());
        } catch(StripeException provider) { throw Problems.of(HttpStatus.BAD_GATEWAY,"Stripe could not prepare this payment. Try again."); }
    }

    @Transactional Trip demo(Account user,String id) {
        if(!demo) throw Problems.missing();
        Ride ride=completed(user,id,true);
        var existing=data.payment(id).orElse(null);
        if(existing!=null&&!existing.status().equals("DEMO_PAID")) throw Problems.conflict("This ride already uses Stripe.");
        if(existing==null) data.payment(id,null,ride.fareMinor,"DEMO_PAID");
        trips.changed(ride,"PAID");return trips.view(ride);
    }

    @Transactional void verify(String body,String signature) {
        if(!enabled()) throw Problems.missing();
        if(signature==null) throw Problems.bad("Stripe signature is required.");
        try {
            var event=Webhook.constructEvent(body,signature,webhook);
            if(!event.getType().equals("payment_intent.succeeded")) return;
            var value=json.readTree(body).path("data").path("object");
            var payment=data.byIntent(value.path("id").asText()).orElse(null);
            if(payment==null) return;
            Ride ride=trips.lock(payment.rideId());
            if(ride.status!=RideStatus.COMPLETED
                ||!ride.id.equals(value.path("metadata").path("rideId").asText())
                ||!"inr".equals(value.path("currency").asText())
                ||!"succeeded".equals(value.path("status").asText())
                ||value.path("amount_received").asLong(-1)!=payment.amountMinor()
                ||payment.amountMinor()!=ride.fareMinor) throw Problems.bad("Stripe payment does not match the ride.");
            if(payment.status().equals("PENDING")) { data.paid(ride.id);trips.changed(ride,"PAID"); }
        } catch(org.springframework.web.server.ResponseStatusException mismatch) { throw mismatch;
        } catch(com.stripe.exception.SignatureVerificationException|IOException|RuntimeException invalid) {
            throw Problems.bad("Stripe webhook could not be verified.");
        }
    }

    byte[] receipt(Account user,String id) {
        Ride r=completed(user,id,false);
        Payment payment=data.payment(id).filter(p->!p.status().equals("PENDING")).orElseThrow(()->Problems.conflict("A paid ride is required for a receipt."));
        try (var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            var page=new PDPage();document.addPage(page);
            try(var canvas=new PDPageContentStream(document,page)) {
                canvas.beginText();canvas.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),13);
                canvas.newLineAtOffset(55,720); canvas.setLeading(25);
                for(String line:List.of("Wayfare | Ride receipt",payment.status().equals("DEMO_PAID")?"Demo receipt - no charge":"Payment received",
                    "Ride: "+id,"Pickup: "+r.pickup,"Destination: "+r.dropoff,"Date: "+r.createdAt.toString().substring(0,10),
                    "Fare: INR "+BigDecimal.valueOf(payment.amountMinor(),2).toPlainString())) {
                    canvas.showText(line.codePoints().mapToObj(c->c>=32&&c<=126?Character.toString(c):"?").reduce("",String::concat));canvas.newLine();
                }
                canvas.endText();
            }
            document.save(output);return output.toByteArray();
        } catch(IOException failure) {throw new IllegalStateException("Receipt generation failed",failure);}
    }

    void email(Account user,String id) {
        if(!mailEnabled||mail.getIfAvailable()==null) throw Problems.of(HttpStatus.SERVICE_UNAVAILABLE,"Receipt email has not been configured.");
        var pdf=receipt(user,id);
        try {
            var sender=mail.getObject();var message=sender.createMimeMessage();
            var helper=new MimeMessageHelper(message,true,"UTF-8");
            helper.setFrom(mailFrom);helper.setTo(user.email());helper.setSubject("Your Wayfare receipt");
            helper.setText("Your ride receipt is attached.");helper.addAttachment("wayfare-receipt.pdf",new ByteArrayResource(pdf));
            sender.send(message);
        } catch(jakarta.mail.MessagingException|org.springframework.mail.MailException failure) {
            throw Problems.of(HttpStatus.BAD_GATEWAY,"The receipt could not be sent.");
        }
    }
}

@RestController @RequestMapping("/api")
class PaymentApi {
    final Billing billing;
    final Trips trips;
    PaymentApi(Billing billing,Trips trips){this.billing=billing;this.trips=trips;}
    @GetMapping("/config") Map<String,Object> config(){return billing.config();}
    @PostMapping("/rides/{id}/payment") PaymentSetup payment(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return billing.initiate(trips.actor(jwt),id);}
    @PostMapping("/rides/{id}/payment/demo") Trip demo(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return billing.demo(trips.actor(jwt),id);}
    @PostMapping("/payments/webhook") @ResponseStatus(HttpStatus.NO_CONTENT)
    void webhook(@RequestBody String body,@RequestHeader(name="Stripe-Signature",required=false) String signature){billing.verify(body,signature);}
    @GetMapping("/rides/{id}/receipt")
    ResponseEntity<byte[]> receipt(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=wayfare-receipt.pdf")
            .body(billing.receipt(trips.actor(jwt),id));
    }
    @PostMapping("/rides/{id}/receipt/email") @ResponseStatus(HttpStatus.NO_CONTENT)
    void email(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){billing.email(trips.actor(jwt),id);}
}
