package io.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

record Point(double lat,double lon) {
    Point checked() {
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180) throw Problems.bad("Choose valid map coordinates.");
        return this;
    }
}
record TripInput(@NotBlank @Size(max=120) String pickup,@NotBlank @Size(max=120) String dropoff,
                 @Valid @NotNull Point from,@Valid @NotNull Point to,@Size(max=16) String promo) {}
record Quote(int meters,int etaMinutes,long fareMinor,String promo,String source,List<Point> route) {}

@Component
class Routing {
    private final Data data;
    private final String base;
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    Routing(Data data,ObjectMapper json,@Value("${app.router}") String base) {
        this.data=data; this.json=json; this.base=base.replaceAll("/+$","");
        if(!this.base.isBlank()) {
            URI uri=URI.create(this.base);
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null) {
                throw new IllegalArgumentException("ROUTER_URL must be the operator's HTTP or HTTPS OSRM origin.");
            }
        }
    }

    static double distance(Point from,Point to) {
        double a=Math.pow(Math.sin(Math.toRadians(to.lat()-from.lat())/2),2)+Math.cos(Math.toRadians(from.lat()))*
                Math.cos(Math.toRadians(to.lat()))*Math.pow(Math.sin(Math.toRadians(to.lon()-from.lon())/2),2);
        return 12742000*Math.asin(Math.sqrt(Math.min(1,a)));
    }
    private static String coordinate(Point p) { return p.lon()+","+p.lat(); }

    Quote quote(TripInput trip) {
        Point from=trip.from().checked(), to=trip.to().checked();
        double direct=distance(from,to);
        if(direct<50||direct>200000) throw Problems.bad("Pickup and destination must be 50 m to 200 km apart.");
        int meters=(int)Math.round(direct*1.2);
        int minutes=Math.max(2,(int)Math.ceil(meters/430.0));
        String source="ESTIMATE";
        List<Point> route=List.of(from,to);
        if(!base.isBlank()) {
            try {
                String path="/route/v1/driving/"+coordinate(from)+";"+coordinate(to)+"?overview=simplified&geometries=geojson";
                var response=client.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()==200) {
                    JsonNode result=json.readTree(response.body()).path("routes").path(0);
                    var coordinates=result.path("geometry").path("coordinates");
                    if(coordinates.isArray()&&coordinates.size()>=2&&coordinates.size()<=10000&&result.path("distance").asDouble()>0) {
                        var pathPoints=new ArrayList<Point>();
                        for(var p:coordinates) pathPoints.add(new Point(p.path(1).asDouble(),p.path(0).asDouble()).checked());
                        meters=(int)Math.round(result.path("distance").asDouble());
                        minutes=Math.max(1,(int)Math.ceil(result.path("duration").asDouble()/60));
                        route=pathPoints; source="OSRM";
                    }
                }
            } catch(InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch(java.io.IOException|IllegalArgumentException|org.springframework.web.server.ResponseStatusException unavailable) {
                // A routing outage falls back to a labeled estimate.
            }
        }
        String promo=trip.promo()==null||trip.promo().isBlank()?null:trip.promo().strip().toUpperCase(Locale.ROOT);
        int discount=promo==null?0:data.discount(promo).orElseThrow(()->Problems.bad("This promo code is not available."));
        long subtotal=3900+Math.round(meters*1.5);
        return new Quote(meters,minutes,Math.max(100,Math.round(subtotal*(100-discount)/100.0)),promo,source,route);
    }
}
