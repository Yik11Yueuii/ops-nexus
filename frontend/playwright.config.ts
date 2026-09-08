import {defineConfig} from '@playwright/test';
export default defineConfig({
 testDir:'./e2e',workers:1,timeout:45000,
 use:{baseURL:'http://127.0.0.1:5173',channel:'msedge',headless:true,viewport:{width:1440,height:1000}},
 outputDir:'../runtime/browser-tests'
});
