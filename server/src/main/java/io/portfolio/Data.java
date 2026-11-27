package io.portfolio;

import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

enum RideStatus { REQUESTED, ACCEPTED, ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED }

@Entity @Table(name="rides")
class Ride {
    @Id String id;
    String riderId;
    String driverId;
    String pickup;
    String dropoff;
    double pickupLat;
    double pickupLon;
    double dropLat;
    double dropLon;
    Double driverLat;
    Double driverLon;
    int meters;
    int etaMinutes;
    long fareMinor;
    String promo;
    @Enumerated(EnumType.STRING) RideStatus status;
    Instant createdAt;
    Instant updatedAt;
    protected Ride() {}
}

interface Rides extends JpaRepository<Ride,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from Ride r where r.id=:id")
    Optional<Ride> lock(@Param("id") String id);
    List<Ride> findByRiderIdOrderByCreatedAtDesc(String user,Pageable page);
    List<Ride> findByDriverIdOrderByCreatedAtDesc(String user,Pageable page);
    List<Ride> findByStatusOrderByCreatedAtDesc(RideStatus status,Pageable page);
    boolean existsByRiderIdAndStatusIn(String user,Collection<RideStatus> status);
    boolean existsByDriverIdAndStatusIn(String user,Collection<RideStatus> status);
}

record Driver(String userId,boolean available,Double lat,Double lon,Instant updatedAt) {}
record Payment(String rideId,String intentId,long amountMinor,String status) {}
record Rating(String rideId,String driverId,int stars,String feedback) {}
record Ticket(String id,String userId,String rideId,String kind,String details,String state,Instant createdAt) {}

@Component
class Data implements IdentityStore {
    final JdbcClient sql;
    Data(JdbcClient sql) { this.sql=sql; }
    private org.springframework.jdbc.core.RowMapper<Account> accountMapper() {
        return (r,n)->new Account(r.getString("id"),r.getString("email"),r.getString("name"),r.getString("password_hash"),r.getString("role"));
    }

    public Optional<Account> byEmail(String email) { return sql.sql("select * from users where email=?").param(email).query(accountMapper()).optional(); }
    public Optional<Account> account(String id) { return sql.sql("select * from users where id=?").param(id).query(accountMapper()).optional(); }
    public void store(Account a) {
        sql.sql("insert into users (id,email,name,password_hash,role) values (?,?,?,?,?)").params(Arrays.asList(a.id(),a.email(),a.name(),a.hash(),a.role())).update();
    }
    public void start(LoginSession s) {
        sql.sql("delete from sessions where expires_at < current_timestamp").update();
        sql.sql("insert into sessions values (?,?,?)").params(s.id(),s.user(),Timestamp.from(s.expires())).update();
    }
    public boolean active(String id,String user) { return sql.sql("select count(*) from sessions where id=? and user_id=? and expires_at>current_timestamp")
        .params(id,user).query(Long.class).single() > 0; }
    public void revoke(String id) { sql.sql("delete from sessions where id=?").param(id).update(); }

    void lockUser(String id) { sql.sql("select id from users where id=? for update").param(id).query(String.class).single(); }

    private Driver driverRow(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Driver(r.getString("user_id"),r.getBoolean("available"),(Double)r.getObject("lat"),(Double)r.getObject("lon"),r.getTimestamp("updated_at").toInstant());
    }

    Optional<Driver> driver(String id) { return sql.sql("select * from drivers where user_id=?").param(id).query(this::driverRow).optional(); }
    void setDriver(String id,boolean enabled,Double lat,Double lon) {
        var params=Arrays.asList(enabled,lat,lon,Timestamp.from(Instant.now()),id);
        var updated=sql.sql("update drivers set available=?, lat=?, lon=?, updated_at=? where user_id=?").params(params).update();
        if(updated==0) sql.sql("insert into drivers(available,lat,lon,updated_at,user_id) values(?,?,?,?,?)").params(params).update();
    }

    List<Driver> nearby(double lat,double lon) {
        double latDelta=.15;
        double lonDelta=Math.min(180,.15/Math.max(.015,Math.cos(Math.toRadians(lat))));
        return sql.sql("select * from drivers where available=true and lat between ? and ? and (abs(lon-?) <= ? or abs(lon-?) >= ?) and updated_at>? fetch first 100 rows only")
            .params(lat-latDelta,lat+latDelta,lon,lonDelta,lon,360-lonDelta,Timestamp.from(Instant.now().minusSeconds(3600)))
            .query(this::driverRow).list();
    }
    Set<String> declined(String user) {
        return new HashSet<>(sql.sql("select ride_id from declined_rides where user_id=?").param(user).query(String.class).list());
    }
    void decline(String user,String ride) {
        sql.sql("insert into declined_rides values (?,?)").params(user,ride).update();
    }

    Optional<Payment> payment(String id) {
        return sql.sql("select * from payments where ride_id=?").param(id)
            .query((r,n)->new Payment(r.getString("ride_id"),r.getString("intent_id"),r.getLong("amount_minor"),r.getString("status"))).optional();
    }
    Optional<Payment> byIntent(String id) {
        return sql.sql("select * from payments where intent_id=?").param(id)
            .query((r,n)->new Payment(r.getString("ride_id"),r.getString("intent_id"),r.getLong("amount_minor"),r.getString("status"))).optional();
    }
    void payment(String ride,String intent,long amount,String state) {
        sql.sql("insert into payments (ride_id,intent_id,amount_minor,status,paid_at) values (?,?,?,?,?)").params(
            Arrays.asList(ride,intent,amount,state,state.endsWith("PAID")?Timestamp.from(Instant.now()):null)).update();
    }
    void paid(String ride) {
        sql.sql("update payments set status='PAID', paid_at=current_timestamp where ride_id=? and status='PENDING'").param(ride).update();
    }
    Optional<Rating> rating(String ride) {
        return sql.sql("select * from ratings where ride_id=?").param(ride)
            .query((r,n)->new Rating(r.getString("ride_id"),r.getString("driver_id"),r.getInt("stars"),r.getString("feedback"))).optional();
    }
    void rate(String ride,String driver,int stars,String feedback) {
        sql.sql("insert into ratings values (?,?,?,?,current_timestamp)").params(ride,driver,stars,feedback).update();
    }
    Map<String,Object> reputation(String driver) {
        return sql.sql("select count(*) as count, coalesce(avg(cast(stars as decimal(5,2))),0) as average from ratings where driver_id=?")
            .param(driver).query((r,n)->Map.<String,Object>of("count",r.getLong(1),"average",r.getDouble(2))).single();
    }
    long earnings(String driver) {
        return sql.sql("select coalesce(sum(p.amount_minor),0) from payments p join rides r on p.ride_id=r.id where r.driver_id=? and p.status in ('PAID','DEMO_PAID')")
            .param(driver).query(Long.class).single();
    }
    List<Map<String,Object>> ranking() {
        return sql.sql("select u.name, avg(cast(t.stars as decimal(5,2))) as average, count(*) as reviews from ratings t join users u on t.driver_id=u.id group by u.id,u.name order by average desc,reviews desc,u.name fetch first 20 rows only")
            .query((r,n)->Map.<String,Object>of("name",r.getString(1),"average",r.getDouble(2),"reviews",r.getLong(3))).list();
    }
    Optional<Integer> discount(String promo) {
        return sql.sql("select discount from promo_codes where code=?").param(promo).query(Integer.class).optional();
    }
    void promo(String code,int discount) {
        if(sql.sql("update promo_codes set discount=? where code=?").params(discount,code).update()==0) {
            sql.sql("insert into promo_codes values (?,?)").params(code,discount).update();
        }
    }
    Ticket support(String user,String ride,String kind,String details) {
        var issue = new Ticket(UUID.randomUUID().toString(),user,ride,kind,details,"OPEN",Instant.now());
        sql.sql("insert into support_tickets values (?,?,?,?,?,?,?)")
            .params(Arrays.asList(issue.id(),user,ride,kind,details,issue.state(),Timestamp.from(issue.createdAt()))).update();
        return issue;
    }
    List<Ticket> tickets() {
        return sql.sql("select * from support_tickets order by created_at desc fetch first 50 rows only")
            .query((r,n)->new Ticket(r.getString("id"),r.getString("user_id"),r.getString("ride_id"),r.getString("kind"),r.getString("details"),r.getString("state"),r.getTimestamp("created_at").toInstant())).list();
    }
    void resolve(String id) {
        if(sql.sql("update support_tickets set state='RESOLVED' where id=?").param(id).update()==0) throw Problems.missing();
    }
    List<String> admins() { return sql.sql("select id from users where role='ADMIN'").query(String.class).list(); }
}

@Configuration
class Bootstrap {
    @Bean ApplicationRunner admin(Auth auth, @Value("${app.admin-email}") String address, @Value("${app.admin-password}") String password) {
        return context -> {
            if (address.isBlank()) return;
            String email=Auth.email(address);
            var account=auth.store.byEmail(email);
            if(account.isPresent()) {
                if(!account.get().role().equals("ADMIN")) throw new IllegalStateException("Admin email already belongs to a non-admin.");
                return;
            }
            if(password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw new IllegalStateException("Set a valid ADMIN_PASSWORD.");
            auth.store.store(new Account(UUID.randomUUID().toString(),email,"Wayfare operations",auth.bcrypt.encode(password),"ADMIN"));
        };
    }
}
