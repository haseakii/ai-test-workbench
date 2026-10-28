import {defineConfig} from '@playwright/test';
export default defineConfig({
 testDir:'tests/e2e',workers:1,fullyParallel:false,timeout:60000,
 use:{baseURL:process.env.WB_BASE_URL??'http://127.0.0.1:18080',headless:true,launchOptions:{executablePath:process.env.CHROMIUM_PATH??'/usr/bin/chromium'},trace:'retain-on-failure',screenshot:'only-on-failure'},
 reporter:[['list'],['json',{outputFile:process.env.WB_UI_REPORT??'../docs/reviews/implementation-d0-d2/ui-report.json'}]],
 outputDir:process.env.WB_UI_OUTPUT??'../docs/reviews/implementation-d0-d2/ui-artifacts'
});
