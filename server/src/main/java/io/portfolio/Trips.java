package io.portfolio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

record Notice(Set<String> users,String type,String ride) {}
record DriverPresence(boolean available,@Valid Point point) {}
record Action(@NotBlank String action) {}
record Review(@Min(1) @Max(5) int stars,@Size(max=500) String feedback) {}
record Support(@Size(max=36) String rideId,@NotBlank @Pattern(regexp="SOS|SUPPORT") String kind,@NotBlank @Size(max=500) String details) {}
record Promo(@NotBlank @Pattern(regexp="[A-Za-z0-9]{4,16}") String code,@Min(1) @Max(80) int discount) {}
record Trip(String id,String pickup,String dropoff,Point from,Point to,Point driverLocation,String status,String rider,String driver,
            int meters,int etaMinutes,long fareMinor,String payment,Rating rating,Instant createdAt) {}

@Service
class Trips {
    static final Set<RideStatus> BUSY=EnumSet.of(RideStatus.ACCEPTED,RideStatus.ARRIVED,RideStatus.IN_PROGRESS);
    static final Set<RideStatus> OPEN=EnumSet.of(RideStatus.REQUESTED,RideStatus.ACCEPTED,RideStatus.ARRIVED,RideStatus.IN_PROGRESS);
    final Data data;
    final Rides rides;
    final ApplicationEventPublisher events;
    Trips(Data data,Rides rides,ApplicationEventPublisher events) { this.data=data;this.rides=rides;this.events=events; }

    Account actor(Jwt jwt) { return data.account(Auth.id(jwt)).orElseThrow(Problems::missing); }
    static void require(Account actor,String role) { if(!actor.role().equals(role)) throw Problems.forbidden(); }
    Ride find(String id) { return rides.findById(id).orElseThrow(Problems::missing); }
    Ride lock(String id) { return rides.lock(id).orElseThrow(Problems::missing); }

    void see(Account owner,Ride ride) {
        if(!owner.role().equals("ADMIN")&&!owner.id().equals(ride.riderId)&&!owner.id().equals(ride.driverId)) throw Problems.missing();
    }
    static Point pickup(Ride r) { return new Point(r.pickupLat,r.pickupLon); }
    static boolean near(Point a,Double lat,Double lon) { return lat!=null&&lon!=null&&Routing.distance(a,new Point(lat,lon))<=15000; }
    static boolean fresh(Driver d) { return d.available()&&d.updatedAt().isAfter(Instant.now().minusSeconds(3600)); }
    private String name(String id) { return id==null?null:data.account(id).map(Account::name).orElse(null); }
    Trip view(Ride r) {
        return new Trip(r.id,r.pickup,r.dropoff,pickup(r),new Point(r.dropLat,r.dropLon),
            r.driverLat==null?null:new Point(r.driverLat,r.driverLon),r.status.name(),name(r.riderId),name(r.driverId),
            r.meters,r.etaMinutes,r.fareMinor,data.payment(r.id).map(Payment::status).orElse("UNPAID"),data.rating(r.id).orElse(null),r.createdAt);
    }

    void changed(Ride r,String event) {
        var audience=new HashSet<String>(); audience.add(r.riderId);
        if(r.driverId!=null) audience.add(r.driverId);
        if(r.status==RideStatus.REQUESTED||event.equals("ACCEPTED")||event.equals("CANCELLED")) {
            for(Driver d:data.nearby(r.pickupLat,r.pickupLon)) if(near(pickup(r),d.lat(),d.lon())) audience.add(d.userId());
        }
        events.publishEvent(new Notice(Set.copyOf(audience),event,r.id));
    }

    @Transactional Trip create(Account owner,TripInput input,Quote quote) {
        require(owner,"RIDER"); data.lockUser(owner.id());
        if(rides.existsByRiderIdAndStatusIn(owner.id(),OPEN)) throw Problems.conflict("Finish or cancel your current ride first.");
        var ride=new Ride();
        ride.id=UUID.randomUUID().toString(); ride.riderId=owner.id(); ride.pickup=input.pickup().strip(); ride.dropoff=input.dropoff().strip();
        ride.pickupLat=input.from().lat(); ride.pickupLon=input.from().lon(); ride.dropLat=input.to().lat(); ride.dropLon=input.to().lon();
        ride.meters=quote.meters(); ride.etaMinutes=quote.etaMinutes(); ride.fareMinor=quote.fareMinor(); ride.promo=quote.promo();
        ride.status=RideStatus.REQUESTED; ride.createdAt=Instant.now(); ride.updatedAt=ride.createdAt;
        rides.saveAndFlush(ride); changed(ride,"REQUESTED"); return view(ride);
    }
    @Transactional Driver presence(Account owner,DriverPresence input) {
        require(owner,"DRIVER"); data.lockUser(owner.id());
        Point p=input.point()==null?null:input.point().checked();
        if(input.available()&&p==null) throw Problems.bad("Set your location before going online.");
        data.setDriver(owner.id(),input.available(),p==null?null:p.lat(),p==null?null:p.lon());
        return data.driver(owner.id()).orElseThrow();
    }
    List<Trip> inbox(Account owner) {
        require(owner,"DRIVER");
        var driver=data.driver(owner.id()).orElse(null);
        if(driver==null||!fresh(driver)) return List.of();
        var hidden=data.declined(owner.id());
        return rides.findByStatusOrderByCreatedAtDesc(RideStatus.REQUESTED,PageRequest.of(0,100)).stream()
            .filter(r->!hidden.contains(r.id)&&near(pickup(r),driver.lat(),driver.lon()))
            .map(r->new Trip(r.id,r.pickup,r.dropoff,pickup(r),new Point(r.dropLat,r.dropLon),null,r.status.name(),
                            null,null,r.meters,r.etaMinutes,r.fareMinor,"UNPAID",null,r.createdAt)).toList();
    }
    List<Trip> history(Account owner,int page) {
        if(page<0||page>5000) throw Problems.bad("Invalid history page.");
        var paged=PageRequest.of(page,40);
        return (owner.role().equals("DRIVER")?rides.findByDriverIdOrderByCreatedAtDesc(owner.id(),paged)
                :rides.findByRiderIdOrderByCreatedAtDesc(owner.id(),paged)).stream().map(this::view).toList();
    }
    @Transactional Trip transition(Account owner,String id,String action) {
        if(!Set.of("ACCEPT","DECLINE","CANCEL","ARRIVE","START","COMPLETE").contains(action)) throw Problems.bad("Unknown ride action.");
        if(action.equals("ACCEPT")||action.equals("DECLINE")) {
            require(owner,"DRIVER"); data.lockUser(owner.id());
            Ride ride=lock(id);
            Driver driver=data.driver(owner.id()).filter(Trips::fresh).orElseThrow(()->Problems.conflict("Go online with a recent location first."));
            if(ride.status!=RideStatus.REQUESTED||!near(pickup(ride),driver.lat(),driver.lon())) throw Problems.conflict("This ride is no longer available.");
            if(action.equals("DECLINE")) {
                if(!data.declined(owner.id()).contains(id)) data.decline(owner.id(),id);
                return null;
            }
            if(data.declined(owner.id()).contains(id)||rides.existsByDriverIdAndStatusIn(owner.id(),BUSY)) throw Problems.conflict("You cannot accept this ride.");
            ride.driverId=owner.id();ride.status=RideStatus.ACCEPTED;ride.driverLat=driver.lat();ride.driverLon=driver.lon();
            ride.updatedAt=Instant.now(); changed(ride,"ACCEPTED"); return view(ride);
        }
        Ride ride=lock(id); see(owner,ride);
        if(action.equals("CANCEL")) {
            require(owner,"RIDER");
            if(!owner.id().equals(ride.riderId)||!Set.of(RideStatus.REQUESTED,RideStatus.ACCEPTED,RideStatus.ARRIVED).contains(ride.status)) {
                throw Problems.conflict("This ride can no longer be cancelled.");
            }
            ride.status=RideStatus.CANCELLED;
        } else {
            require(owner,"DRIVER");
            if(!owner.id().equals(ride.driverId)) throw Problems.missing();
            RideStatus target=switch(action) {
                case "ARRIVE" -> ride.status==RideStatus.ACCEPTED?RideStatus.ARRIVED:null;
                case "START" -> ride.status==RideStatus.ARRIVED?RideStatus.IN_PROGRESS:null;
                case "COMPLETE" -> ride.status==RideStatus.IN_PROGRESS?RideStatus.COMPLETED:null;
                default -> null;
            };
            if(target==null) throw Problems.conflict("This ride cannot make that status transition.");
            ride.status=target;
        }
        ride.updatedAt=Instant.now();changed(ride,ride.status.name());return view(ride);
    }

    @Transactional Trip track(Account owner,String id,Point point) {
        require(owner,"DRIVER"); point.checked();
        Ride ride=lock(id);
        if(!owner.id().equals(ride.driverId)) throw Problems.missing();
        if(!BUSY.contains(ride.status)) throw Problems.conflict("Tracking is available during an active assigned ride.");
        ride.driverLat=point.lat();ride.driverLon=point.lon();changed(ride,"LOCATION");return view(ride);
    }

    @Transactional Trip rate(Account owner,String id,Review review) {
        require(owner,"RIDER"); Ride ride=lock(id);
        if(!owner.id().equals(ride.riderId)) throw Problems.missing();
        if(ride.status!=RideStatus.COMPLETED||data.rating(id).isPresent()) throw Problems.conflict("A completed ride can be rated once.");
        data.rate(id,ride.driverId,review.stars(),Objects.toString(review.feedback(),"").strip());
        changed(ride,"RATED"); return view(ride);
    }

    @Transactional Ticket support(Account owner,Support input) {
        if(input.rideId()!=null) { Ride ride=find(input.rideId()); see(owner,ride);
            if(input.kind().equals("SOS")&&!BUSY.contains(ride.status)) throw Problems.conflict("SOS alerts require an active assigned ride.");
        } else if(input.kind().equals("SOS")) throw Problems.bad("Choose an active ride for an SOS alert.");
        var ticket=data.support(owner.id(),input.rideId(),input.kind(),input.details().strip());
        events.publishEvent(new Notice(Set.copyOf(data.admins()),input.kind(),input.rideId()));
        return ticket;
    }

    Map<String,Object> operations(Account owner) {
        require(owner,"ADMIN");
        return Map.of("rides",rides.findAll(PageRequest.of(0,40,org.springframework.data.domain.Sort.by("createdAt").descending()))
                    .stream().map(this::view).toList(),
            "totals",data.sql.sql("select status,count(*) from rides group by status").query((r,n)->Map.of("status",r.getString(1),"count",r.getLong(2))).list(),
            "paymentTotals",data.sql.sql("select status,count(*) from payments group by status").query((r,n)->Map.of("status",r.getString(1),"count",r.getLong(2))).list(),
            "support",data.tickets(),"ranking",data.ranking(),
            "promos",data.sql.sql("select code,discount from promo_codes order by code").query((r,n)->Map.of("code",r.getString(1),"discount",r.getInt(2))).list());
    }
}

@RestController @RequestMapping("/api")
class TripApi {
    final Trips trips;
    final Routing routing;
    final Data data;
    TripApi(Trips trips,Routing routing,Data data) { this.trips=trips;this.routing=routing;this.data=data; }
    @PostMapping("/rides/quote") Quote quote(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody TripInput input) {
        Trips.require(trips.actor(jwt),"RIDER"); return routing.quote(input);
    }
    @PostMapping("/rides") @ResponseStatus(HttpStatus.CREATED) Trip book(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody TripInput input) {
        Account user=trips.actor(jwt); Trips.require(user,"RIDER"); return trips.create(user,input,routing.quote(input));
    }
    @GetMapping("/rides") List<Trip> history(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page) { return trips.history(trips.actor(jwt),page); }
    @GetMapping("/rides/{id}") Trip read(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
        Ride ride=trips.find(id);trips.see(trips.actor(jwt),ride);return trips.view(ride);
    }
    @PatchMapping("/rides/{id}/status") ResponseEntity<Trip> transition(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@Valid @RequestBody Action input) {
        var result=trips.transition(trips.actor(jwt),id,input.action());
        return result==null?ResponseEntity.noContent().build():ResponseEntity.ok(result);
    }
    @PutMapping("/rides/{id}/location") Trip track(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody Point point) {
        return trips.track(trips.actor(jwt),id,point);
    }
    @PostMapping("/rides/{id}/rating") Trip rating(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@Valid @RequestBody Review input) {
        return trips.rate(trips.actor(jwt),id,input);
    }
    @PutMapping("/driver/presence") Driver presence(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody DriverPresence input) { return trips.presence(trips.actor(jwt),input); }
    @GetMapping("/driver/requests") List<Trip> nearby(@AuthenticationPrincipal Jwt jwt) { return trips.inbox(trips.actor(jwt)); }
    @GetMapping("/driver/stats") Map<String,Object> stats(@AuthenticationPrincipal Jwt jwt) {
        Account user=trips.actor(jwt);Trips.require(user,"DRIVER");
        return Map.of("earnings",data.earnings(user.id()),"rating",data.reputation(user.id()),
            "presence",data.driver(user.id()).orElse(new Driver(user.id(),false,null,null,Instant.EPOCH)));
    }
    @GetMapping("/drivers/ranking") List<Map<String,Object>> ranking() { return data.ranking(); }
    @PostMapping("/support") Ticket support(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Support input) { return trips.support(trips.actor(jwt),input); }
    @GetMapping("/admin") Map<String,Object> admin(@AuthenticationPrincipal Jwt jwt) { return trips.operations(trips.actor(jwt)); }
    @PutMapping("/admin/promos") @ResponseStatus(HttpStatus.NO_CONTENT) void promo(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Promo input) {
        Trips.require(trips.actor(jwt),"ADMIN");data.promo(input.code().toUpperCase(Locale.ROOT),input.discount());
    }
    @PatchMapping("/admin/support/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void resolve(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
        Trips.require(trips.actor(jwt),"ADMIN");data.resolve(id);
    }
}
