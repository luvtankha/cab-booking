create table users (
    id varchar(36) primary key, email varchar(254) not null unique, name varchar(70) not null,
    password_hash varchar(100), role varchar(16) not null check (role in ('RIDER','DRIVER','ADMIN'))
);
create table sessions (
    id varchar(36) primary key, user_id varchar(36) not null references users(id) on delete cascade,
    expires_at timestamp with time zone not null
);
create index sessions_expiry on sessions(expires_at);
create table drivers (
    user_id varchar(36) primary key references users(id) on delete cascade, available boolean not null,
    lat double precision, lon double precision, updated_at timestamp with time zone not null
);
create index driver_matching on drivers(available,lat,lon);
create table rides (
    id varchar(36) primary key, rider_id varchar(36) not null references users(id), driver_id varchar(36) references users(id),
    pickup varchar(120) not null, dropoff varchar(120) not null,
    pickup_lat double precision not null, pickup_lon double precision not null,
    drop_lat double precision not null, drop_lon double precision not null,
    driver_lat double precision, driver_lon double precision,
    meters integer not null, eta_minutes integer not null, fare_minor bigint not null, promo varchar(16), status varchar(16) not null,
    created_at timestamp with time zone not null, updated_at timestamp with time zone not null
);
create index rider_history on rides(rider_id,created_at desc);
create index driver_history on rides(driver_id,created_at desc);
create index ride_requests on rides(status,created_at desc);
create table declined_rides (
    user_id varchar(36) not null references users(id), ride_id varchar(36) not null references rides(id),
    primary key(user_id,ride_id)
);
create table payments (
    ride_id varchar(36) primary key references rides(id), intent_id varchar(128) unique,
    amount_minor bigint not null, status varchar(18) not null, paid_at timestamp with time zone
);
create table ratings (
    ride_id varchar(36) primary key references rides(id), driver_id varchar(36) not null references users(id),
    stars integer not null check (stars between 1 and 5), feedback varchar(500) not null, created_at timestamp with time zone not null
);
create index rating_driver on ratings(driver_id);
create table promo_codes (code varchar(16) primary key, discount integer not null check (discount between 1 and 80));
create table support_tickets (
    id varchar(36) primary key, user_id varchar(36) not null references users(id),
    ride_id varchar(36) references rides(id), kind varchar(12) not null, details varchar(500) not null,
    state varchar(12) not null, created_at timestamp with time zone not null
);
create index support_status on support_tickets(state,created_at desc);
