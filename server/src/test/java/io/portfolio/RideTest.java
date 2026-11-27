package io.portfolio;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "app.stripe-secret=sk_test_isolated_fixture","app.stripe-public=pk_test_isolated_fixture","app.stripe-signing=whsec_isolated_fixture"
})
class RideTest {
    @Autowired Data database;
    @DynamicPropertySource static void config(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",()->System.getenv().getOrDefault("TEST_DATABASE_URL",
            "jdbc:h2:mem:wayfare_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
    }

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @BeforeEach void client() { http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory()); }
    record Result(int status, String body, ObjectMapper mapper) {
        JsonNode json() throws Exception { return mapper.readTree(body); }
    }
    Result call(HttpMethod method,String path,String bearer,Object body) {
        return call(method,path,bearer,body,Map.of());
    }
    Result call(HttpMethod method,String path,String bearer,Object body,Map<String,String> extra) {
        var headers=new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if(bearer!=null) headers.setBearerAuth(bearer);
        extra.forEach(headers::set);
        var response=http.exchange(path,method,new HttpEntity<>(body,headers),String.class);
        return new Result(response.getStatusCode().value(),response.getBody(),mapper);
    }
    String account(String role) throws Exception {
        var result=call(HttpMethod.POST,"/api/auth/signup",null,Map.of("name","Test "+role,"email",UUID.randomUUID()+"@example.com",
               "password","isolated-suite-password","role",role));
        assertThat(result.status()).isEqualTo(201);
        return result.json().path("token").asText();
    }

    @Test void permissionsStateTransitionsAndVerifiedIdempotentPayment() throws Exception {
        String rider=account("RIDER"), driver=account("DRIVER"), outsider=account("RIDER"), declined=account("DRIVER");
        assertThat(call(HttpMethod.GET,"/api/admin",rider,null).status()).isEqualTo(403);
        var from=Map.of("lat",28.632,"lon",77.217);
        var to=Map.of("lat",28.61,"lon",77.23);
        var presence=Map.of("available",true,"point",from);
        assertThat(call(HttpMethod.PUT,"/api/driver/presence",driver,presence).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT,"/api/driver/presence",declined,presence).status()).isEqualTo(200);
        var trip=Map.of("pickup","Market","dropoff","Gallery","from",from,"to",to);
        var created=call(HttpMethod.POST,"/api/rides",rider,trip);
        assertThat(created.status()).isEqualTo(201);
        var json=created.json();String id=json.path("id").asText();long amount=json.path("fareMinor").asLong();String path="/api/rides/"+id;
        assertThat(call(HttpMethod.GET,path,outsider,null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.PATCH,path+"/status",declined,Map.of("action","DECLINE")).status()).isEqualTo(204);
        assertThat(call(HttpMethod.GET,"/api/driver/requests",declined,null).json().size()).isZero();
        assertThat(call(HttpMethod.PATCH,path+"/status",driver,Map.of("action","ACCEPT")).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT,path+"/location",driver,to).status()).isEqualTo(200);
        assertThat(call(HttpMethod.GET,path,rider,null).json().path("driverLocation").path("lat").asDouble()).isEqualTo(28.61);
        assertThat(call(HttpMethod.PATCH,path+"/status",driver,Map.of("action","COMPLETE")).status()).isEqualTo(409);
        for(String action:List.of("ARRIVE","START","COMPLETE"))
            assertThat(call(HttpMethod.PATCH,path+"/status",driver,Map.of("action",action)).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST,path+"/rating",rider,Map.of("stars",5,"feedback","Smooth ride")).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST,path+"/rating",rider,Map.of("stars",4)).status()).isEqualTo(409);
        database.payment(id,"pi_fixture",amount,"PENDING");
        var event="{\"id\":\"evt_fixture\",\"object\":\"event\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_fixture\",\"object\":\"payment_intent\",\"status\":\"succeeded\",\"currency\":\"inr\",\"amount_received\":"+amount+",\"metadata\":{\"rideId\":\""+id+"\"}}}}";
        assertThat(call(HttpMethod.POST,"/api/payments/webhook",null,event,Map.of("Stripe-Signature","invalid")).status()).isEqualTo(400);
        assertThat(database.payment(id).orElseThrow().status()).isEqualTo("PENDING");
        long now=Instant.now().getEpochSecond();
        var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_isolated_fixture".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String valid="t="+now+",v1="+HexFormat.of().formatHex(mac.doFinal((now+"."+event).getBytes(StandardCharsets.UTF_8)));
        for(int i=0;i<2;i++) assertThat(call(HttpMethod.POST,"/api/payments/webhook",null,event,Map.of("Stripe-Signature",valid)).status()).isEqualTo(204);
        assertThat(database.payment(id).orElseThrow().status()).isEqualTo("PAID");
        assertThat(call(HttpMethod.GET,path+"/receipt",outsider,null).status()).isEqualTo(404);
        var headers=new HttpHeaders();headers.setBearerAuth(rider);
        var pdf=http.exchange(path+"/receipt",HttpMethod.GET,new HttpEntity<Void>(headers),byte[].class);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(new String(pdf.getBody(),0,4,StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }
}
