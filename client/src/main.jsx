import React from 'react';
import { createRoot } from 'react-dom/client';
import { AuthProvider } from './shared.jsx';
import App from './App.jsx';
import './base.css';
import './app.css';
createRoot(document.getElementById('root')).render(<AuthProvider><App /></AuthProvider>);
