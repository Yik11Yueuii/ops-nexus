import {test,expect} from '@playwright/test';
const adminPassword=process.env.DEMO_ADMIN_PASSWORD,userPassword=process.env.DEMO_USER_PASSWORD;
async function login(page:any,username:'admin'|'user'){
 const password=username==='admin'?adminPassword:userPassword;if(!password)throw new Error('请设置 DEMO_ADMIN_PASSWORD 和 DEMO_USER_PASSWORD');
 await page.goto('/');await page.getByLabel('用户名',{exact:true}).fill(username);await page.getByLabel('密码',{exact:true}).fill(password);await page.getByRole('button',{name:'登录',exact:true}).click();
}
test('工作台展示运营概览且移动端无横向溢出',async({page})=>{
 await login(page,'admin');await expect(page.getByRole('heading',{name:'今天，从可靠知识开始判断。'})).toBeVisible();
 await expect(page.getByText('有效知识库',{exact:true})).toBeVisible();await page.screenshot({path:'../runtime/screenshots/dashboard-desktop.png',fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:'../runtime/screenshots/dashboard-mobile.png',fullPage:true});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBeTruthy();
});
test('管理员阅读版本、上传预览并删除本次测试草稿',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 await login(page,'admin');
 await page.locator('aside').getByRole('button',{name:/知识文档/}).click();
 await expect(page.getByRole('heading',{name:'让团队经验，有处可寻。'})).toBeVisible();
 await page.getByRole('button',{name:/订单服务部署手册/}).click();
 await expect(page.getByRole('button',{name:/v2.0/})).toBeVisible();
 await page.getByRole('button',{name:/v1.0/}).click();
 await expect(page.locator('.preview')).toContainText('最大连接数为 8');
 await page.getByRole('button',{name:/v2.0/}).click();
 await expect(page.locator('.preview')).toContainText('最大连接数调整为 16');
 await page.screenshot({path:'../runtime/screenshots/knowledge-desktop.png',fullPage:true});
 const title='浏览器验证-'+Date.now();let versionId:number|undefined;
 try{
  await page.getByRole('button',{name:'＋ 上传文档',exact:true}).click();
  await page.getByLabel('选择文件').setInputFiles({name:'browser-test.txt',mimeType:'text/plain',buffer:Buffer.from('本次浏览器验证正文 '+title)});
  await page.getByLabel('文档标题',{exact:true}).fill(title);
  const uploaded=page.waitForResponse(r=>r.url().endsWith('/documents')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'上传并解析'}).click();
  versionId=(await (await uploaded).json()).data.versionId;
  await expect(page.locator('.detail-heading')).toContainText(title);
  await expect(page.locator('.preview')).toContainText('本次浏览器验证正文');
  await expect(page.getByRole('button',{name:'执行向量化'})).toBeEnabled();
  page.once('dialog',d=>d.accept());
  await page.getByRole('button',{name:'删除草稿',exact:true}).click();
  await expect(page.getByRole('button',{name:new RegExp(title)})).toHaveCount(0);
  versionId=undefined;
 } finally {
  if(versionId) {
   const token=await page.evaluate(()=>sessionStorage.getItem('ops-token'));
   await page.request.delete('/api/document-versions/'+versionId,{headers:{Authorization:'Bearer '+token}});
  }
 }
 await page.setViewportSize({width:390,height:844});
 await page.screenshot({path:'../runtime/screenshots/knowledge-mobile.png',fullPage:true});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBeTruthy();
 expect(errors).toEqual([]);
});
test('普通用户只看到已发布资料且没有上传入口',async({page})=>{
 await login(page,'user');
 await page.locator('aside').getByRole('button',{name:/知识文档/}).click();
 await expect(page.getByRole('heading',{name:'让团队经验，有处可寻。'})).toBeVisible();
 await expect(page.getByRole('button',{name:'＋ 上传文档',exact:true})).toHaveCount(0);
 await expect(page.getByText('已发布的当前版本')).toBeVisible();
});
test('知识助手流式回答并展示真实引用',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 await login(page,'admin');
 await page.locator('aside').getByRole('button',{name:/知识助手/}).click();
 await expect(page.getByRole('heading',{name:'知识助手'})).toBeVisible();
 await page.getByRole('button',{name:'分析 Redis 排障路径'}).click();
 const completed=page.waitForResponse(r=>r.url().endsWith('/api/assistant/chat/stream')&&r.request().method()==='POST');
 await page.getByRole('button',{name:'发送',exact:true}).click();
 expect((await completed).ok()).toBeTruthy();
 await expect(page.getByText(/可信等级：(MEDIUM|HIGH)/)).toBeVisible({timeout:45000});
 await expect(page.getByText('引用依据',{exact:true})).toBeVisible();
 await expect(page.locator('.citations')).toContainText('Redis 连接池耗尽排障 SOP');
 await expect(page.locator('.message.assistant pre')).not.toContainText('正在检索并生成回答');
 await page.screenshot({path:'../runtime/screenshots/assistant-desktop.png',fullPage:true});
 expect(errors).toEqual([]);
});
test('故障诊断组合证据并可标记解决',async({page})=>{
 test.setTimeout(90000);
 await login(page,'admin');await page.locator('aside').getByRole('button',{name:/故障诊断/}).click();
 await expect(page.getByRole('heading',{name:'故障辅助诊断'})).toBeVisible();
 await page.getByLabel('故障现象').fill('Redis 获取连接超时，订单接口延迟升高');
 await page.getByLabel('错误日志与上下文').fill('生产环境，发布 2.3.1 后出现，连接池 active 接近上限');
 await page.getByRole('button',{name:'开始诊断'}).click();
 await expect(page.getByText(/诊断报告 #[1-9]\d*/)).toBeVisible({timeout:60000});
 await expect(page.getByText('最近发布',{exact:true})).toBeVisible();await expect(page.getByText('历史故障',{exact:true})).toBeVisible();await expect(page.getByText('SOP 引用',{exact:true})).toBeVisible();
 const resolve=page.getByRole('button',{name:'标记解决'}).first();await expect(resolve).toBeVisible();await resolve.click();await expect(page.getByText('已解决').first()).toBeVisible();
 await page.screenshot({path:'../runtime/screenshots/diagnosis-desktop.png',fullPage:true});
});
test('普通用户使用固定只读业务工具且看不到管理员分析',async({page})=>{
 await login(page,'user');await page.getByRole('button',{name:/业务查询/}).click();
 await page.getByRole('button',{name:'查询最近发布'}).click();await page.getByRole('button',{name:'查询',exact:true}).click();
 await expect(page.getByText('latest_releases',{exact:true})).toBeVisible();await expect(page.getByText(/service = order-service/)).toBeVisible();
 await expect(page.getByRole('button',{name:/数据分析/})).toHaveCount(0);
});
test('管理员数据分析展示安全 SQL、结果和审计',async({page})=>{
 test.setTimeout(60000);await login(page,'admin');await page.getByRole('button',{name:/数据分析/}).click();
 await expect(page.getByRole('heading',{name:'智能数据分析'})).toBeVisible();await page.getByRole('button',{name:'使用演示问题'}).click();
 await page.getByRole('button',{name:'开始分析'}).click();await expect(page.getByText('AST 审核通过',{exact:false})).toBeVisible({timeout:45000});
 await expect(page.locator('.sql-card')).toContainText('LIMIT 100');await expect(page.locator('.audit-panel')).toContainText('SUCCESS');
 const token=await page.evaluate(()=>sessionStorage.getItem('ops-token'));const logs=await page.request.get('/api/admin/ai-calls',{headers:{Authorization:'Bearer '+token}});expect(logs.ok()).toBeTruthy();expect(JSON.stringify((await logs.json()).data)).toContain('TEXT_TO_SQL');
 await page.screenshot({path:'../runtime/screenshots/analytics-desktop.png',fullPage:true});
});
test('管理员完成文档版本对比',async({page})=>{
 await login(page,'admin');await page.locator('aside').getByRole('button',{name:/版本对比/}).click();await expect(page.getByRole('heading',{name:'文档版本对比'})).toBeVisible();
 await page.getByLabel('文档').selectOption({label:'订单服务部署手册'});await page.getByLabel('旧版本').selectOption({label:'v1.0'});await page.getByLabel('新版本').selectOption({label:'v2.0'});
 await page.getByRole('button',{name:'开始对比'}).click();await expect(page.getByText('v1.0 → v2.0')).toBeVisible();await expect(page.locator('.risk-panel')).toContainText(/运行参数|发布流程/);
 await page.screenshot({path:'../runtime/screenshots/version-compare-desktop.png',fullPage:true});
});
test('证据不足问题进入知识缺口并可处理',async({page})=>{
 const question='星云科技量子厨房的月球菜单审批流程是什么？';await login(page,'admin');await page.locator('aside').getByRole('button',{name:/知识助手/}).click();
 await page.locator('.composer textarea').fill(question);await page.getByRole('button',{name:'发送',exact:true}).click();await expect(page.getByText(/没有找到足够的已发布依据/)).toBeVisible({timeout:30000});
 await page.locator('aside').getByRole('button',{name:/缺口雷达/}).click();const gap=page.locator('.gap-row').filter({hasText:question});await expect(gap).toBeVisible();await gap.getByRole('button',{name:'标记已处理'}).click();await expect(gap.getByText('已解决')).toBeVisible();
 await page.screenshot({path:'../runtime/screenshots/knowledge-gap-desktop.png',fullPage:true});
});
