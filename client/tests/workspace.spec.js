import { randomUUID } from 'node:crypto';
import { test,expect } from '@playwright/test';

async function enter(page,role){
  await page.route('https://tile.openstreetmap.org/**',route=>route.abort());
  await page.goto('/');
  await page.getByRole('button',{name:'Create an account'}).click();
  await page.getByLabel('Name',{exact:true}).fill(role==='DRIVER'?'Jamie Chen':'Alex Morgan');
  await page.getByLabel('Email address').fill(randomUUID()+'@example.com');
  await page.getByLabel('Password').fill('isolated-test-password-01');
  await page.getByLabel('Account type').selectOption(role);
  await page.getByRole('button',{name:'Create account',exact:true}).click();
  await expect(page.getByText('Live updates',{exact:true})).toBeVisible({timeout:15000});
}

test('rider and driver share the trip lifecycle, payment, and receipt',async({page,browser},info)=>{
  await enter(page,'RIDER');
  await page.getByRole('button',{name:'Sample route'}).click();
  await page.getByRole('button',{name:'Get fare estimate'}).click();
  await expect(page.getByText('Distance estimate',{exact:true})).toBeVisible();
  if(info.project.name==='mobile-webkit'){
    expect(await page.locator('body').evaluate(el=>el.scrollWidth<=window.innerWidth)).toBe(true);
    return;
  }
  const context=await browser.newContext();
  try{
    const driver=await context.newPage();
    await enter(driver,'DRIVER');
    await driver.getByRole('button',{name:'Use sample location'}).click();
    await expect(driver.getByRole('button',{name:'Go offline'})).toBeVisible();
    await page.getByRole('button',{name:'Request ride',exact:true}).click();
    await expect(driver.getByRole('button',{name:'Accept',exact:true}).first()).toBeVisible({timeout:16000});
    await driver.getByRole('button',{name:'Accept',exact:true}).first().click();
    const ride=page.locator('.current-trip');
    await expect(ride.getByText('Driver assigned',{exact:true})).toBeVisible();
    await driver.getByRole('button',{name:'Mark arrived'}).click();
    await expect(page.getByRole('status').filter({hasText:'Your driver has marked arrival.'})).toBeVisible();
    await driver.getByRole('button',{name:'Start ride',exact:true}).click();
    await expect(ride.getByText('On the road',{exact:true})).toBeVisible();
    await driver.getByRole('button',{name:'Complete ride',exact:true}).click();
    await page.getByRole('button',{name:'Record demo payment'}).click();
    await expect(ride.getByText('Demo payment recorded',{exact:true})).toBeVisible();
    const download=page.waitForEvent('download');
    await page.getByRole('button',{name:'PDF receipt',exact:true}).click();
    expect((await download).suggestedFilename()).toBe('wayfare-receipt.pdf');
    await page.getByRole('button',{name:'Rate driver',exact:true}).click();
    await page.getByLabel('Feedback').fill('Test ride went smoothly.');
    await page.getByRole('button',{name:'Save rating'}).click();
    await expect(ride.getByText('5/5 submitted',{exact:true})).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading',{name:'Ride history'})).toBeVisible();
    await expect(page.getByRole('button',{name:/Connaught Place.*India Gate/}).first()).toBeVisible();
  }finally{await context.close();}
});
