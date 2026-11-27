import { resolve } from 'node:path';
import { defineConfig, devices } from '@playwright/test';

const app = 'http://127.0.0.1:5331';
const backend = 'http://127.0.0.1:7711';
export default defineConfig({
  testDir:'tests',testMatch:'**/*.spec.js',timeout:95000,workers:2,
  use:{baseURL:app,screenshot:'only-on-failure',trace:'retain-on-failure'},
  projects:[{name:'chromium',use:{...devices['Desktop Chrome']}},{name:'mobile-webkit',use:{...devices['iPhone 15'],defaultBrowserType:'webkit'}}],
  webServer:[
    {command:'java -XX:ActiveProcessorCount=2 -jar target/cab-booking-1.0.0.jar',cwd:resolve(import.meta.dirname,'../server'),url:backend+'/actuator/health',timeout:90000,
      env:{PORT:'7711',JWT_SECRET:'isolated-playwright-signing-key-portfolio-project',DEMO_PAYMENTS:'true',DATABASE_URL:process.env.TEST_DATABASE_URL || 'jdbc:h2:mem:wayfare_e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'}},
    {command:'npm run dev -- --strictPort --port 5331',url:app,timeout:90000,env:{APP_BACKEND:backend}}
  ]
});
