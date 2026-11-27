import { useCallback, useEffect, useRef, useState } from 'react';
import { Activity, ArrowDownToLine, ArrowRight, Bell, CarFront, CreditCard, Gauge, LocateFixed, LogOut, MapPin, Navigation, Radio, ShieldCheck, Star, TriangleAlert } from 'lucide-react';
import { Client } from '@stomp/stompjs';
import { loadStripe } from '@stripe/stripe-js/pure';
import { api, AuthPage, Brand, ErrorBox, Modal, money, Progress, save, token, useAuth } from './shared.jsx';
import Map, {center} from './Map.jsx';

const states={REQUESTED:'Requested',ACCEPTED:'Driver assigned',ARRIVED:'Driver arrived',IN_PROGRESS:'On the road',COMPLETED:'Completed',CANCELLED:'Cancelled'};
const active=new Set(['ACCEPTED','ARRIVED','IN_PROGRESS']);
const actions={ACCEPTED:['ARRIVE','Mark arrived'],ARRIVED:['START','Start ride'],IN_PROGRESS:['COMPLETE','Complete ride']};
const distances = meters => (meters / 1000).toFixed(1)+' km';
const defaultTrip={pickup:'',dropoff:'',from:null,to:null,promo:''};
const spots=[{name:'Connaught Place',point:{lat:28.63150,lon:77.21670}},{name:'India Gate',point:{lat:28.61290,lon:77.22950}}];
const date=new Intl.DateTimeFormat('en-IN',{dateStyle:'medium',timeStyle:'short'});

function locationNow(){
  return new Promise((resolve,reject)=>{
    if(!navigator.geolocation){reject(Error('Location is unavailable on this device.'));return;}
    navigator.geolocation.getCurrentPosition(p=>resolve({lat:p.coords.latitude,lon:p.coords.longitude}),
      ()=>reject(Error('Location access was unavailable. Use the map or enter coordinates.')),{timeout:9000,maximumAge:15000,enableHighAccuracy:true});
  });
}

function useLive(onRefresh){
  const [live,setLive]=useState(false);
  const [event,setEvent]=useState('');
  useEffect(()=>{
    let valid=true;
    const proto=location.protocol==='https:'?'wss:':'ws:';
    const client=new Client({brokerURL:proto+'//'+location.host+'/ws',connectHeaders:{Authorization:'Bearer '+token()},reconnectDelay:4000,
      heartbeatIncoming:0,heartbeatOutgoing:0,connectionTimeout:6500});
    client.onConnect=()=>{
      if(!valid)return;
      setLive(true);
      client.subscribe('/user/queue/live',message=>{
        const update=JSON.parse(message.body);
        if(update.type==='ARRIVED')setEvent('Your driver has marked arrival.');
        if(update.type==='SOS')setEvent('A new SOS alert needs review.');
        onRefresh();
      });
      onRefresh();
    };
    client.onWebSocketClose=()=>{if(valid)setLive(false);};
    client.activate();
    return()=>{valid=false;client.deactivate().catch(()=>{});};
  },[onRefresh]);
  return {live,event,clear:()=>setEvent('')};
}

function Status({value}) {return <span className={'status '+value.toLowerCase()}>{states[value]||value}</span>;}

function StripeCard({config,secret,close,done}){
  const locationRef=useRef(null), controls=useRef(null);
  const [ready,setReady]=useState(false), [busy,setBusy]=useState(false), [error,setError]=useState('');
  useEffect(()=>{
    let mounted=true;
    loadStripe(config.stripeKey).then(stripe=>{
      if(!mounted)return;
      if(!stripe)throw Error('Stripe could not load.');
      const elements=stripe.elements({clientSecret:secret});
      const payment=elements.create('payment');
      controls.current={stripe,elements,payment};
      payment.on('ready',()=>setReady(true));
      payment.on('loaderror',()=>setError('Stripe could not load its payment form.'));
      payment.mount(locationRef.current);
    }).catch(e=>{if(mounted)setError(e.message);});
    return()=>{mounted=false;controls.current?.payment.destroy();};
  },[config.stripeKey,secret]);
  return <Modal title="Pay for your ride" close={close}><form className="stack modal-body" onSubmit={async e=>{
    e.preventDefault();if(!ready||busy)return;
    const {stripe,elements}=controls.current;setBusy(true);setError('');
    try{
      const outcome=await stripe.confirmPayment({elements,confirmParams:{return_url:location.origin},redirect:'if_required'});
      if(outcome.error)throw Error(outcome.error.message);
      done();
    }catch(err){setError(err.message);}finally{setBusy(false);}
  }}><div ref={locationRef}/><ErrorBox>{error}</ErrorBox><button className="button primary" disabled={!ready||busy}>{busy?'Submitting…':'Confirm payment'}</button></form></Modal>;
}

function SupportForm({kind,ride,close,done}){
  const [error,setError]=useState(''),[busy,setBusy]=useState(false);
  return <Modal title={kind==='SOS'?'Send an SOS alert':'Contact support'} close={close}>
    <form className="stack modal-body" onSubmit={async e=>{
      e.preventDefault();const details=new FormData(e.currentTarget).get('details');
      setBusy(true);setError('');
      try{await api('/support',{method:'POST',body:{kind,rideId:ride?.id||null,details}});done(kind==='SOS'?'Alert sent to Wayfare operations.':'Support ticket submitted.');}
      catch(err){setError(err.message);}finally{setBusy(false);}
    }}>
    {kind==='SOS'&&<p className="muted">This alerts Wayfare operations. Contact local emergency services for immediate help.</p>}
    <label>Describe what happened<textarea name="details" rows={4} maxLength={500} required/></label><ErrorBox>{error}</ErrorBox>
    <button className={'button '+(kind==='SOS'?'danger':'primary')} disabled={busy}>{busy?'Sending…':kind==='SOS'?'Send SOS':'Send ticket'}</button>
    </form>
  </Modal>;
}

function RatingForm({ride,close,done}){
  const [error,setError]=useState(''),[busy,setBusy]=useState(false);
  return <Modal title="Rate your driver" close={close}><form className="stack modal-body" onSubmit={async e=>{
    e.preventDefault();const data=new FormData(e.currentTarget);
    setBusy(true);setError('');
    try{await api('/rides/'+ride.id+'/rating',{method:'POST',body:{stars:Number(data.get('stars')),feedback:data.get('feedback')}});done('Rating saved.');}
    catch(err){setError(err.message);}finally{setBusy(false);}
  }}><label>Stars<select name="stars" defaultValue="5">{[5,4,3,2,1].map(n=><option value={n} key={n}>{n} of 5</option>)}</select></label>
    <label>Feedback<textarea name="feedback" maxLength={500} rows={3}/></label><ErrorBox>{error}</ErrorBox>
    <button className="button primary" disabled={busy}>Save rating</button></form></Modal>;
}

function RiderForm({onPoint,onTrip,plan,setPlan,quote,setQuote,mode,setMode,run}){
  const set=(field,value)=>{setPlan(prev=>({...prev,[field]:value}));setQuote(null);};
  const valid=plan.pickup.trim()&&plan.dropoff.trim()&&plan.from&&plan.to;
  return <section className="panel booking"><header><span className="eyebrow">Rider dashboard</span><h1>Plan a ride</h1></header>
    <form className="stack" onSubmit={e=>{e.preventDefault();if(valid)run(()=>api('/rides/quote',{method:'POST',body:plan}).then(setQuote));}}>
      {[['pickup','from','Pickup address'],['dropoff','to','Destination address']].map(([key,point,label])=><div className="address" key={key}>
        <label>{label}<input value={plan[key]} maxLength={120} required onChange={e=>set(key,e.target.value)} placeholder={key==='pickup'?'Where are you now?':'Where are you going?'}/></label>
        <div className="coord-controls"><button type="button" className="button secondary" aria-pressed={mode===point} onClick={()=>setMode(point)}><MapPin size={14}/>{point==='from'?'Pin pickup':'Pin destination'}</button>
          <small>{plan[point]?plan[point].lat.toFixed(4)+', '+plan[point].lon.toFixed(4):'Choose a pin on the map'}</small></div>
      </div>)}
      <div className="form-row"><button type="button" className="button secondary" onClick={()=>run(async()=>onPoint(await locationNow(),'from'))}><LocateFixed size={15}/> Use my location</button>
        <button type="button" className="button secondary" onClick={()=>{setPlan({pickup:spots[0].name,dropoff:spots[1].name,from:spots[0].point,to:spots[1].point,promo:''});setQuote(null);}}>Sample route</button></div>
      <label>Promo code <span className="muted">(optional)</span><input value={plan.promo} maxLength={16} onChange={e=>set('promo',e.target.value)}/></label>
      <button className="button primary" disabled={!valid}>Get fare estimate <ArrowRight size={16}/></button>
    </form>
    {quote&&<div className="quote"><div><small>{quote.source==='OSRM'?'Road route':'Distance estimate'}</small>
      <strong>{money(quote.fareMinor)}</strong><span>{distances(quote.meters)} · {quote.etaMinutes} min</span></div>
      <button className="button primary" onClick={()=>run(()=>api('/rides',{method:'POST',body:plan}).then(onTrip))}>Request ride</button></div>}
  </section>;
}

function TripPanel({ride,user,config,run,refresh,setModal,notify}){
  const path='/rides/'+ride.id;
  const trackable=user.role==='DRIVER'&&active.has(ride.status);
  const [sharing, setSharing]=useState(false);
  const watcher=useRef(null);
  const lastUpload=useRef(0);
  const stop=()=>{if(watcher.current!==null)navigator.geolocation.clearWatch(watcher.current);watcher.current=null;};
  useEffect(()=>{setSharing(false);return stop;},[ride.id,trackable]);
  function toggleLocation(){
    if(sharing){stop();setSharing(false);return;}
    if(!navigator.geolocation){notify('Location sharing is unavailable on this device.');return;}
    lastUpload.current=0;
    watcher.current=navigator.geolocation.watchPosition(position=>{
      if(Date.now()-lastUpload.current<8000)return;
      lastUpload.current=Date.now();
      api(path+'/location',{method:'PUT',body:{lat:position.coords.latitude,lon:position.coords.longitude}}).catch(error=>{
        stop();setSharing(false);notify(error.message);
      });
    },()=>{stop();setSharing(false);notify('Location sharing stopped. Check your device permissions.');},
    {timeout:12000,maximumAge:10000,enableHighAccuracy:true});
    setSharing(true);
  }
  const change=action=>run(async()=>{await api(path+'/status',{method:'PATCH',body:{action}});refresh();});
  const paid=['PAID','DEMO_PAID'].includes(ride.payment);
  return <section className="panel current-trip">
    <header className="panel-head"><h2>Ride details</h2><Status value={ride.status}/></header>
    <dl className="route-details"><div><dt>Pickup</dt><dd>{ride.pickup}</dd></div><div><dt>Destination</dt><dd>{ride.dropoff}</dd></div>
      {ride.driver&&<div><dt>Driver</dt><dd>{ride.driver}</dd></div>}{ride.rider&&user.role==='DRIVER'&&<div><dt>Rider</dt><dd>{ride.rider}</dd></div>}
      <div><dt>Fare</dt><dd>{money(ride.fareMinor)}</dd></div><div><dt>Distance</dt><dd>{distances(ride.meters)} · {ride.etaMinutes} min</dd></div></dl>
    <div className="trip-actions">
      {user.role==='RIDER'&&['REQUESTED','ACCEPTED','ARRIVED'].includes(ride.status)&&<button className="button secondary" onClick={()=>change('CANCEL')}>Cancel ride</button>}
      {user.role==='DRIVER'&&actions[ride.status]&&<button className="button primary" onClick={()=>change(actions[ride.status][0])}>{actions[ride.status][1]}</button>}
      {trackable&&<button className="button secondary" aria-pressed={sharing} onClick={toggleLocation}><Navigation size={16}/> {sharing?'Stop sharing location':'Share live location'}</button>}
      {active.has(ride.status)&&<button className="button danger" onClick={()=>setModal({type:'SOS',ride})}><TriangleAlert size={16}/> SOS</button>}
      {user.role==='RIDER'&&ride.status==='COMPLETED'&&<>
        {paid?<><span className="paid">{ride.payment==='DEMO_PAID'?'Demo payment recorded':'Paid'}</span>
          <button className="button secondary" onClick={()=>run(async()=>save(await api(path+'/receipt',{binary:true}),'wayfare-receipt.pdf'))}><ArrowDownToLine size={16}/> PDF receipt</button>
          {config.receiptEmail&&<button className="button secondary" onClick={()=>run(async()=>{await api(path+'/receipt/email',{method:'POST'});notify('Receipt emailed.');})}>Email receipt</button>}</>:<>
          {config.stripe&&<button className="button primary" onClick={()=>run(async()=>{const data=await api(path+'/payment',{method:'POST'});
            if(data.clientSecret)setModal({type:'stripe',secret:data.clientSecret});else{refresh();notify('Waiting for payment verification.');}})}><CreditCard size={16}/> Pay with Stripe</button>}
          {config.demo&&<button className="button primary" onClick={()=>run(async()=>{await api(path+'/payment/demo',{method:'POST'});refresh();})}>Record demo payment</button>}
          {!config.stripe&&!config.demo&&<span className="muted">Payments are unavailable in this environment.</span>}
          {ride.payment==='PENDING'&&<span className="muted">Payment verification pending</span>}
        </>}
        {ride.rating?<span className="paid"><Star size={15}/> {ride.rating.stars}/5 submitted</span>:<button className="button secondary" onClick={()=>setModal({type:'rating',ride})}><Star size={16}/> Rate driver</button>}
      </>}
    </div>
  </section>;
}

function Operations({data,run,refresh}){
  if(!data)return <Progress>Loading operations…</Progress>;
  return <div className="ops">
    <header className="page-title"><div><span className="eyebrow">Admin dashboard</span><h1>Operations</h1></div></header>
    <div className="status-grid">{[...data.totals,...data.paymentTotals].map(group=><div className="panel stat" key={group.status}><span>{states[group.status]||group.status}</span><strong>{group.count}</strong></div>)}</div>
    <section className="panel"><header className="panel-head"><h2>Ride activity</h2><Activity size={19}/></header>
      {data.rides.map(r=><div className="list-line" key={r.id}><span><strong>{r.pickup} → {r.dropoff}</strong><small>{r.rider} · {date.format(new Date(r.createdAt))}</small></span><Status value={r.status}/></div>)}</section>
    <section className="panel"><header className="panel-head"><h2>Support and SOS</h2><TriangleAlert size={19}/></header>
      {data.support.length?data.support.map(t=><div className="list-line" key={t.id}><span><strong>{t.kind} · {t.state}</strong><small>{t.details}</small></span>
        {t.state==='OPEN'&&<button className="button secondary" onClick={()=>run(async()=>{await api('/admin/support/'+t.id,{method:'PATCH'});refresh();})}>Resolve</button>}</div>):<p className="muted">No support tickets.</p>}</section>
    <section className="panel"><header className="panel-head"><h2>Promo codes</h2></header><form className="promo" onSubmit={e=>{
      e.preventDefault();const f=new FormData(e.target);run(async()=>{await api('/admin/promos',{method:'PUT',body:{code:f.get('code'),discount:Number(f.get('discount'))}});refresh();});
    }}><label>Code<input name="code" pattern="[A-Za-z0-9]{4,16}" required/></label><label>Discount %<input name="discount" type="number" min="1" max="80" required/></label>
      <button className="button primary">Save promo</button></form><ul>{data.promos.map(p=><li key={p.code}>{p.code} · {p.discount}%</li>)}</ul></section>
  </div>;
}

function Dashboard({user,config}) {
  const {logout}=useAuth(), rider=user.role==='RIDER',driver=user.role==='DRIVER',admin=user.role==='ADMIN';
  const [plan,setPlan]=useState(defaultTrip),[quote,setQuote]=useState(null),[mode,setMode]=useState('from');
  const [history,setHistory]=useState([]),[nearby,setNearby]=useState([]),[stats,setStats]=useState(null),[rank,setRank]=useState([]),[ops,setOps]=useState(null);
  const [selected,select]=useState(null),[notice,notify]=useState(''),[error,setError]=useState(''),[modal,setModal]=useState(null);
  const [version,setVersion]=useState(0),[waiting,setWaiting]=useState(true),[pending,setPending]=useState(false);
  const lock=useRef(false);
  const refresh=useCallback(()=>setVersion(x=>x+1),[]);
  const live=useLive(refresh);
  async function run(action){
    if(lock.current)return;
    lock.current=true;setError('');setPending(true);
    try{await action();}catch(err){setError(err.message);}finally{lock.current=false;setPending(false);}
  }
  useEffect(()=>{
    let keep=true;
    const load=admin?api('/admin').then(value=>{if(keep){setOps(value);setRank(value.ranking);}}):
      Promise.all([api('/rides'),api('/drivers/ranking'),driver?api('/driver/requests'):null,driver?api('/driver/stats'):null])
        .then(([rides,ranking,requests,driverStats])=>{if(keep){setHistory(rides);setRank(ranking);setNearby(requests||[]);setStats(driverStats);}});
    load.catch(err=>{if(keep)setError(err.message);}).finally(()=>{if(keep)setWaiting(false);});
    return()=>{keep=false;};
  },[version,admin,driver]);
  useEffect(()=>{
    window.addEventListener('focus',refresh);
    const timer=setInterval(refresh,22000);
    return()=>{window.removeEventListener('focus',refresh);clearInterval(timer);};
  },[refresh]);

  const ongoing=history.find(trip=>active.has(trip.status)||trip.status==='REQUESTED');
  const trip=history.find(item=>item.id===selected)||(selected?null:ongoing);
  const setPoint=(point,field=mode)=>{setPlan(prev=>({...prev,[field]:point}));setQuote(null);};
  const online=stats?.presence?.available;
  function presence(point,available=true){return api('/driver/presence',{method:'PUT',body:{point,available}}).then(refresh);}
  const finish=message=>{setModal(null);notify(message);refresh();};
  const vehicle=stats?.presence?.lat==null?null:{lat:stats.presence.lat,lon:stats.presence.lon};

  return <div className="wayfare">
    <header className="topbar"><Brand/><div className="top-actions"><span className="live"><i className={live.live?'on':''}/>{live.live?'Live updates':'Refreshing periodically'}</span>
      <span className="owner-name">{user.name}</span><button className="icon" aria-label="Sign out" onClick={()=>logout().catch(()=>{})}><LogOut size={18}/></button></div></header>
    <main className="way-main">
      <ErrorBox>{error}</ErrorBox>
      {(notice||live.event)&&<div className="banner" role="status"><Bell size={16}/>{notice||live.event}<button className="text-button" onClick={()=>{notify('');live.clear();}}>Dismiss</button></div>}
      {waiting&&<Progress>Loading dashboard…</Progress>}
      {pending&&<Progress>Saving update…</Progress>}
      {admin?<Operations data={ops} run={run} refresh={refresh}/>:<>
        <div className="dispatch">
          <div className="dispatch-controls">
            {rider&&!ongoing&&<RiderForm plan={plan} setPlan={setPlan} onPoint={setPoint} quote={quote} setQuote={setQuote} mode={mode} setMode={setMode}
              run={run} onTrip={value=>{select(value.id);setQuote(null);refresh();}}/>}
            {driver&&<section className="panel driver"><header><span className="eyebrow">Driver dashboard</span><h1>Drive with Wayfare</h1></header>
              <div className="driver-stat"><span><Gauge size={16}/> Earnings <strong>{money(stats?.earnings||0)}</strong></span>
                <span><Star size={16}/> Rating <strong>{stats?.rating?.count?stats.rating.average.toFixed(1):'—'}</strong></span></div>
              <div className="trip-actions"><button className="button primary" onClick={()=>run(async()=>presence(online?vehicle:await locationNow(),!online))}>
                <Radio size={15}/>{online?'Go offline':'Go online with location'}</button>
                <button className="button secondary" onClick={()=>run(()=>presence(center))}>Use sample location</button></div>
              <p className="muted">Click the map to set your pickup area. Matching uses a 15 km radius.</p>
            </section>}
            {trip&&<TripPanel ride={trip} user={user} config={config} run={run} refresh={refresh} setModal={setModal} notify={notify}/>}
          </div>
          <section className="panel map-panel">
            <header className="panel-head"><div><h2>{trip?'Trip map':'Service area'}</h2><p>{driver?'Set your location and view your assigned ride.':trip?'Follow pickup, destination, and driver location.':mode==='from'?'Click to set the pickup pin.':'Click to set the destination pin.'}</p></div><MapPin size={20}/></header>
            <Map from={trip?.from || (rider?plan.from:null)} to={trip?.to || (rider?plan.to:null)} driver={trip?.driverLocation||vehicle}
              line={!trip?quote?.route:null} onPoint={point=>{if(rider&&!ongoing)setPoint(point);if(driver)run(async()=>{if(trip&&active.has(trip.status))await api('/rides/'+trip.id+'/location',{method:'PUT',body:point});else await presence(point);refresh();});}}/>
          </section>
        </div>
        {driver&&<section className="panel requests"><header className="panel-head"><h2>Nearby requests</h2><CarFront size={19}/></header>
          {!nearby.length&&<p className="muted">{online?'No nearby rides right now.':'Go online to receive nearby requests.'}</p>}
          {nearby.map(r=><div className="list-line" key={r.id}><span><strong>{r.pickup} → {r.dropoff}</strong><small>{distances(r.meters)} · {r.etaMinutes} min · {money(r.fareMinor)}</small></span>
            <div className="trip-actions">{['ACCEPT','DECLINE'].map(action=><button key={action} className={'button '+(action==='ACCEPT'?'primary':'secondary')}
              onClick={()=>run(async()=>{await api('/rides/'+r.id+'/status',{method:'PATCH',body:{action}});if(action==='ACCEPT')select(r.id);refresh();})}>{action==='ACCEPT'?'Accept':'Decline'}</button>)}</div></div>)}
        </section>}
        <section className="panel history"><header className="panel-head"><h2>Ride history</h2><button className="button secondary" onClick={()=>setModal({type:'SUPPORT',ride:null})}><ShieldCheck size={16}/> Contact support</button></header>
          {history.length===0&&<p className="muted">Your rides will appear here.</p>}
          {history.map(r=><button className="list-line history-row" key={r.id} onClick={()=>{select(r.id);window.scrollTo({top:0,behavior:'smooth'});}}>
            <span><strong>{r.pickup} → {r.dropoff}</strong><small>{date.format(new Date(r.createdAt))} · {money(r.fareMinor)}</small></span><Status value={r.status}/></button>)}</section>
      </>}
      <section className="panel ranking"><header className="panel-head"><h2>Driver rankings</h2><Star size={18}/></header>
        {!rank.length?<p className="muted">Rankings appear after the first rated ride.</p>:<ol>{rank.map((r,i)=><li key={i}>
          <span>{r.name}</span><strong>{r.average.toFixed(1)}/5 · {r.reviews} {r.reviews===1?'review':'reviews'}</strong></li>)}</ol>}</section>
    </main>
    {['SOS','SUPPORT'].includes(modal?.type)&&<SupportForm kind={modal.type} ride={modal.ride} close={()=>setModal(null)} done={finish}/>}
    {modal?.type==='rating'&&<RatingForm ride={modal.ride} close={()=>setModal(null)} done={finish}/>}
    {modal?.type==='stripe'&&<StripeCard config={config} secret={modal.secret} close={()=>setModal(null)} done={()=>finish('Payment submitted. Waiting for Stripe verification.')}/>}
  </div>;
}

export default function App(){
  const {user,loading}=useAuth();
  const [config,setConfig]=useState({});
  useEffect(()=>{api('/config').then(setConfig).catch(()=>{});},[]);
  if(loading)return <Progress>Opening Wayfare…</Progress>;
  return user?<Dashboard key={user.id} user={user} config={config}/>:<AuthPage config={config}/>;
}
