import { createContext, useContext, useEffect, useRef, useState } from 'react';
import { ArrowRight, CarFront, X } from 'lucide-react';

const storageKey = 'wayfare-session';
const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '');
export const token = () => sessionStorage.getItem(storageKey);

export async function api(path, options = {}) {
  const { body, binary, ...rest } = options;
  const jwt = token();
  let response;
  try {
    response = await fetch(base + '/api' + path, {
      ...rest, body: body === undefined ? undefined : JSON.stringify(body),
      headers: { ...(jwt ? { Authorization: 'Bearer ' + jwt } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      cache: 'no-store', signal: AbortSignal.timeout(23000)
    });
  } catch { throw Error('Server unavailable. Try again shortly.'); }
  if (response.ok) return response.status === 204 ? null : binary ? response.blob() : response.json();
  const data = await response.json().catch(() => null);
  if (response.status === 401 && jwt && jwt === token() && !['/auth/login','/auth/signup'].includes(path)) {
    sessionStorage.removeItem(storageKey);
    window.dispatchEvent(new Event(storageKey));
  }
  const error = Error(data?.detail || (response.status === 401 ? 'Sign in again.' : response.status === 404 ? 'That record is unavailable.' : 'The request could not be completed.'));
  error.status = response.status;
  throw error;
}

export function save(blob, name) {
  const href = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = href; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(href), 10000);
}

const authContext = createContext();
export const useAuth = () => useContext(authContext);
export function AuthProvider({children}) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(Boolean(token()));
  useEffect(() => {
    const expire = () => setUser(null);
    window.addEventListener(storageKey, expire);
    if (token()) api('/auth/me').then(setUser).catch(() => {}).finally(() => setLoading(false));
    return () => window.removeEventListener(storageKey, expire);
  }, []);
  const login = ({token: jwt, user: account}) => {
    sessionStorage.setItem(storageKey, jwt); setUser(account);
  };
  const logout = async () => {
    try { await api('/auth/logout', {method:'POST'}); }
    finally { sessionStorage.removeItem(storageKey); setUser(null); }
  };
  return <authContext.Provider value={{user, loading, login, logout}}>{children}</authContext.Provider>;
}

export const brand = 'Wayfare';
export function Brand() { return <span className="brand"><span className="brand-symbol"><CarFront size={19} /></span>{brand}</span>; }
export function ErrorBox({children}) { return children ? <p className="error" role="alert">{children}</p> : null; }
export function Progress({children='Loading…'}) { return <p className="progress" role="status"><i />{children}</p>; }
export function Modal({title, children, close}) {
  const element = useRef(null);
  useEffect(() => {
    const dialog = element.current; dialog.showModal();
    return () => dialog.close();
  }, []);
  return <dialog ref={element} className="modal" aria-label={title} onCancel={(event)=>{event.preventDefault();close();}}>
    <div className="modal-head"><h2>{title}</h2><button className="icon" type="button" aria-label="Close dialog" onClick={close}><X size={19}/></button></div>{children}
  </dialog>;
}

export function AuthPage({config}) {
  const {login} = useAuth();
  const [signup,setSignup] = useState(false);
  const [error,setError] = useState('');
  const [busy,setBusy] = useState(false);
  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const code = params.get('oauth');
    const failure = params.get('oauth_error');
    if (!code && !failure) return;
    history.replaceState(null,'',location.pathname);
    if (failure) {setError(failure==='password'?'Use the password for this existing account.':'Google sign-in could not be completed.');return;}
    setBusy(true);
    api('/auth/google/exchange',{method:'POST',body:{code}}).then(login).catch(e=>setError(e.message)).finally(()=>setBusy(false));
  }, []);
  async function submit(event) {
    event.preventDefault();
    const body = Object.fromEntries(new FormData(event.currentTarget));
    setBusy(true); setError('');
    try { login(await api('/auth/'+(signup?'signup':'login'),{method:'POST',body})); }
    catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }
  return <main className="auth">
    <section className="auth-main"><Brand/><div className="auth-card">
      <h1>{signup?'Create your account':'Welcome back'}</h1>
      <p className="muted">Book a ride or open your driver dashboard.</p>
      <form className="stack" onSubmit={submit}>
      {signup && <label>Name<input name="name" autoComplete="name" maxLength={70} required /></label>}
      <label>Email address<input name="email" type="email" autoComplete="email" maxLength={254} required placeholder="you@example.com"/></label>
      <label>Password<input name="password" type="password" required minLength={signup?12:undefined} maxLength={72} autoComplete={signup?'new-password':'current-password'} /></label>
      {signup && <label>Account type<select name="role"><option value="RIDER">Rider</option><option value="DRIVER">Driver</option></select></label>}
      <ErrorBox>{error}</ErrorBox><button className="button primary justify-between" disabled={busy}>{busy?'Please wait…':signup?'Create account':'Sign in'}<ArrowRight size={17}/></button>
      </form>
      {config?.google && <a className="button secondary google" href="/oauth2/authorization/google">Continue with Google</a>}
      <p className="auth-switch">{signup?'Already have an account?':'New here?'} <button className="text-button" onClick={()=>{setSignup(!signup);setError('');}}>{signup?'Sign in':'Create an account'}</button></p>
    </div></section>
    <aside className="auth-aside" aria-hidden="true"><div className="art-grid"/><div className="art-symbol"><CarFront size={47}/></div><div className="art-lines"><i/><i/><i/><i/><i/></div></aside>
  </main>;
}

const rupees = new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:2});
export const money = (minor = 0) => rupees.format(minor / 100);
