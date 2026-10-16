// Actual Chromium UI flow, at desktop and iPad-sized viewports. This does not emulate Safari's engine.
const {chromium} = require('playwright');
const fs = require('fs');
const assert = require('assert');
(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROMIUM || '/usr/bin/chromium',headless:true,args:['--no-sandbox']});
  try {
    for (const [label, viewport] of [['desktop',{width:1280,height:900}],['tablet',{width:820,height:1180}]]) {
      const context = await browser.newContext({viewport,acceptDownloads:true,httpCredentials:{username:'tester',password:'test-password'}});
      const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
      const directory='网页目录-'+label+'-'+Date.now();
      page.on('dialog',dialog=>dialog.type()==='prompt'?dialog.accept(directory):dialog.accept());
      const base='http://127.0.0.1:'+process.argv[2];
      await page.goto(base);await page.getByRole('link',{name:'你好.txt',exact:true}).waitFor();
      await page.getByText('匿名访问 · 只读',{exact:true}).waitFor();assert(await page.locator('#pick').isHidden());
      await page.getByRole('link',{name:'账号登录',exact:true}).click();
      await page.getByText('账号访问 · 可上传、覆盖、删除',{exact:true}).waitFor();
      await page.getByRole('button',{name:'新建文件夹',exact:true}).click();
      await page.getByRole('link',{name:directory,exact:true}).waitFor();
      await page.getByRole('link',{name:directory,exact:true}).click();
      await page.getByText('此文件夹为空',{exact:true}).waitFor();
      const payload=Buffer.from('浏览器直接上传\n'+label+'\n'.repeat(4096));const name='上传-'+label+'.txt';
      await page.locator('#upload').setInputFiles({name,mimeType:'text/plain',buffer:payload});
      await page.getByRole('link',{name,exact:true}).waitFor();
      await page.getByText('已上传 1 个文件',{exact:true}).waitFor();
      // Same-name replacement follows the explicit browser confirmation path.
      const replacement=Buffer.from('覆盖验证-'+label);
      const replaced=page.waitForResponse(r=>r.request().method()==='PUT'&&r.url().includes('/api/file'));
      await page.locator('#upload').setInputFiles({name,mimeType:'text/plain',buffer:replacement});
      assert.equal((await replaced).status(),200);
      await page.getByText('已上传 1 个文件',{exact:true}).waitFor();
      const downloadPromise=page.waitForEvent('download');await page.getByRole('link',{name,exact:true}).click();
      const download=await downloadPromise;assert.equal(download.suggestedFilename(),name);
      assert.deepEqual(fs.readFileSync(await download.path()),replacement);
      await page.getByRole('button',{name:'删除',exact:true}).click();await page.getByText('此文件夹为空',{exact:true}).waitFor();
      await page.getByRole('button',{name:'上一级',exact:true}).click();
      await page.getByRole('link',{name:directory,exact:true}).waitFor();
      await page.locator('.row').filter({has:page.getByRole('link',{name:directory,exact:true})}).getByRole('button',{name:'删除'}).click();
      await page.getByRole('link',{name:directory,exact:true}).waitFor({state:'detached'});
      assert.deepEqual(errors,[]);await page.screenshot({path:'/tmp/lanftp-web-'+label+'.png',fullPage:true});
      console.log('PASS '+label+': guest read-only, account login, mkdir, Unicode upload, overwrite, download byte comparison, delete');
      await context.close();
    }
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
