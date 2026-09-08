<script setup lang="ts">
import {ref,computed,onMounted,onUnmounted} from 'vue';
const props=defineProps<{admin:boolean}>();
type Base={id:number;name:string;description:string;documentCount:number};
type Doc={id:number;title:string;versionCount:number;currentVersionId:number|null;latestAt:string};
type Version={id:number;versionNo:string;fileType:string;fileSize:number;originalName:string;processStatus:string;publishStatus:string;chunkCount:number;failureReason:string|null;createdAt:string};
type Chunk={id:number;chunkIndex:number;pageNumber:number|null;content:string};
const bases=ref<Base[]>([]),selectedBase=ref<number>(0),docs=ref<Doc[]>([]),selectedDoc=ref<Doc|null>(null),versions=ref<Version[]>([]),selectedVersion=ref<Version|null>(null),chunks=ref<Chunk[]>([]);
const search=ref(''),error=ref(''),notice=ref(''),loading=ref(false),busy=ref(false),cap=ref({embeddingConfigured:false,embeddingModel:'text-embedding-v2',vectorError:null as string|null});
const showUpload=ref(false),showBase=ref(false),uploadTarget=ref<Doc|null>(null),title=ref(''),versionNo=ref('v1.0'),file=ref<File|null>(null),baseName=ref(''),baseDescription=ref('');
const currentBase=computed(()=>bases.value.find(b=>b.id===selectedBase.value));
const filtered=computed(()=>docs.value.filter(d=>d.title.toLowerCase().includes(search.value.toLowerCase())));
const stage=(v:Version)=>v.processStatus==='READY'?(v.publishStatus==='PUBLISHED'?'已发布':v.publishStatus==='ARCHIVED'?'已归档':'待发布'):({PARSED:'待向量化',PROCESSING:'向量化中',FAILED:'处理失败',PENDING:'等待处理'}[v.processStatus]||v.processStatus);
let timer:ReturnType<typeof setInterval>|undefined;
let selection=0;
async function api(path:string,options:RequestInit={}) {
 const headers:Record<string,string>={Authorization:'Bearer '+(sessionStorage.getItem('ops-token')||'')};
 if(!(options.body instanceof FormData))headers['Content-Type']='application/json';
 const res=await fetch('/api'+path,{...options,headers:{...headers,...options.headers}});
 const body=await res.json();
 if(!res.ok)throw new Error(body.message||'请求失败，请重试');
 return body.data;
}
async function perform(fn:()=>Promise<void>){busy.value=true;error.value='';notice.value='';try{await fn();}catch(e){error.value=e instanceof Error?e.message:'连接失败';}finally{busy.value=false;}}
async function loadBases(){bases.value=await api('/knowledge-bases');if(!selectedBase.value && bases.value.length)selectedBase.value=bases.value[0].id;}
async function loadDocs(){if(selectedBase.value)docs.value=await api('/knowledge-bases/'+selectedBase.value+'/documents');else docs.value=[];}
async function switchBase(){selection++;selectedDoc.value=null;selectedVersion.value=null;versions.value=[];chunks.value=[];await perform(loadDocs);}
async function chooseDoc(doc:Doc){
 const request=++selection;selectedDoc.value=doc;selectedVersion.value=null;chunks.value=[];versions.value=[];error.value='';loading.value=true;
 try{const data=await api('/documents/'+doc.id+'/versions');if(request!==selection)return;versions.value=data;if(data.length)await chooseVersion(data[0],request);}
 catch(e){if(request===selection)error.value=e instanceof Error?e.message:'加载失败';}finally{if(request===selection)loading.value=false;}
}
async function chooseVersion(v:Version,request=++selection){selectedVersion.value=v;chunks.value=[];try{const data=await api('/document-versions/'+v.id+'/chunks');if(request===selection)chunks.value=data;}catch(e){if(request===selection)error.value=e instanceof Error?e.message:'预览失败';}}
async function refreshSelected(){
 if(!selectedDoc.value)return;
 const id=selectedDoc.value.id; const request=selection;
 const data:Version[]=await api('/documents/'+id+'/versions');if(request!==selection)return;
 versions.value=data;selectedVersion.value=data.find(v=>v.id===selectedVersion.value?.id)||data[0]||null;
}
function openUpload(target:Doc|null=null){uploadTarget.value=target;title.value=target?.title||'';versionNo.value=target?'':'v1.0';file.value=null;showUpload.value=true;error.value='';}
function pickFile(e:Event){file.value=(e.target as HTMLInputElement).files?.[0]||null;if(file.value&&!title.value)title.value=file.value.name.replace(/\.[^.]+$/,'');}
async function upload(){await perform(async()=>{
 if(!file.value)throw new Error('请选择文件');if(file.value.size>20*1024*1024)throw new Error('文件不能超过 20 MB');
 const form=new FormData();form.append('file',file.value);form.append('title',title.value);form.append('versionNo',versionNo.value);
 if(uploadTarget.value)form.append('documentId',String(uploadTarget.value.id));
 const result=await api('/knowledge-bases/'+selectedBase.value+'/documents',{method:'POST',body:form});
 showUpload.value=false;await loadBases();await loadDocs();
 let target=uploadTarget.value?docs.value.find(d=>d.id===uploadTarget.value?.id):docs.value.find(d=>d.title===title.value.trim());
 if(target){await chooseDoc(target);const v=versions.value.find(v=>v.id===result.versionId);if(v)await chooseVersion(v);}
 notice.value='上传完成，正文已解析并切分。完成向量化后即可发布。';
});}
async function createBase(){await perform(async()=>{const result=await api('/knowledge-bases',{method:'POST',body:JSON.stringify({name:baseName.value,description:baseDescription.value})});showBase.value=false;await loadBases();selectedBase.value=result.id;await switchBase();notice.value='知识库已创建';});}
async function action(kind:string){
 const v=selectedVersion.value;if(!v)return;
 if(kind==='delete'&&!window.confirm('确认删除这个未发布的草稿及原文件？'))return;
 await perform(async()=>{
 await api('/document-versions/'+v.id+(kind==='delete'?'':'/'+kind),{method:kind==='delete'?'DELETE':'POST'});
 await loadBases();await loadDocs();
 if(kind==='delete'){const doc=docs.value.find(d=>d.id===selectedDoc.value?.id);if(doc)await chooseDoc(doc);else{selectedDoc.value=null;selectedVersion.value=null;versions.value=[];chunks.value=[];}}
 else await refreshSelected();
 notice.value=kind==='process'?'已提交向量化，请稍候。':kind==='publish'?'已发布，旧版本已自动归档。':kind==='archive'?'该版本已归档。':'草稿已删除。';
 });
}
async function download(){await perform(async()=>{
 const v=selectedVersion.value;if(!v)return;
 const res=await fetch('/api/document-versions/'+v.id+'/download',{headers:{Authorization:'Bearer '+sessionStorage.getItem('ops-token')}});
 if(!res.ok)throw new Error((await res.json()).message||'下载失败');
 const url=URL.createObjectURL(await res.blob());const a=document.createElement('a');a.href=url;a.download=v.originalName;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});}
onMounted(async()=>{await perform(async()=>{await loadBases();await loadDocs();if(props.admin)cap.value=await api('/admin/knowledge/capabilities');if(docs.value.length)await chooseDoc(docs.value[0]);});timer=setInterval(()=>{if(versions.value.some(v=>v.processStatus==='PROCESSING'))refreshSelected().catch(()=>{});},2000);});
onUnmounted(()=>{if(timer)clearInterval(timer);selection++;});
</script>
<template>
 <section class="knowledge">
  <div class="kb-heading"><div><span class="eyebrow">KNOWLEDGE WORKSPACE</span><h1>让团队经验，有处可寻。</h1><p class="muted">管理技术资料，保留版本依据，为可信问答准备知识。</p></div><button v-if="admin" @click="openUpload()" :disabled="!selectedBase">＋ 上传文档</button></div>
  <div v-if="error" class="banner error" role="alert">{{error}}</div><div v-if="notice" class="banner success" role="status">{{notice}}</div>
  <div class="kb-stats"><div><small>当前知识库</small><strong>{{currentBase?.name||'尚无知识库'}}</strong></div><div><small>逻辑文档</small><strong>{{docs.length}} <em>份</em></strong></div><div><small>版本总数</small><strong>{{docs.reduce((n,d)=>n+d.versionCount,0)}} <em>个</em></strong></div><div><small>{{admin?'向量模型':'访问范围'}}</small><strong class="small-stat">{{admin?(cap.embeddingConfigured?'已配置 · 可向量化':'待配置密钥'):'已发布的当前版本'}}</strong></div></div>
  <div v-if="admin&&!cap.embeddingConfigured" class="model-note"><span class="dot"></span><div><b>先整理知识，再接入模型</b><p>正文解析与预览可用。当前未配置模型密钥，资料将保留为草稿；完成向量化后才能发布。</p></div></div>
  <div v-if="cap.vectorError" class="banner error">{{cap.vectorError}}</div>
  <div class="library-toolbar"><div class="base-picker"><label for="base-choice">知识库</label><select id="base-choice" v-model="selectedBase" @change="switchBase"><option v-for="b in bases" :key="b.id" :value="b.id">{{b.name}}</option></select><button v-if="admin" class="text-btn" @click="showBase=true">＋ 新建</button></div><input v-model="search" class="search" placeholder="搜索文档标题…" aria-label="搜索文档标题"></div>
  <div class="library-grid">
   <section class="document-list"><div class="list-caption"><b>文档目录</b><span>{{filtered.length}} 项</span></div>
    <button v-for="d in filtered" :key="d.id" class="document-row" :class="{selected:selectedDoc?.id===d.id}" @click="chooseDoc(d)"><span class="doc-icon">▤</span><span class="document-info"><b>{{d.title}}</b><small>{{d.versionCount}} 个版本 · {{d.currentVersionId?'有发布版本':'草稿资料'}}</small></span><span class="chevron">›</span></button>
    <div v-if="!filtered.length" class="empty"><h3>{{search?'没有匹配资料':'知识库等待第一份资料'}}</h3><p>{{admin?'上传部署手册、技术规范或排障 SOP。':'管理员发布资料后，你可以在这里阅读。'}}</p><button v-if="admin&&!search" @click="openUpload()" :disabled="!selectedBase">上传文档</button></div>
   </section>
   <section class="document-detail" v-if="selectedDoc">
    <div class="detail-heading"><div><small class="muted">文档详情 / 版本与正文</small><h2>{{selectedDoc.title}}</h2></div><button v-if="admin" class="outline" @click="openUpload(selectedDoc)">上传新版本</button></div>
    <div class="version-tabs"><button v-for="v in versions" :key="v.id" :class="{active:selectedVersion?.id===v.id}" @click="chooseVersion(v)">{{v.versionNo}} <span>{{stage(v)}}</span></button></div>
    <template v-if="selectedVersion">
     <div class="file-meta"><span class="pill">{{selectedVersion.fileType}}</span><span>{{(selectedVersion.fileSize/1024).toFixed(1)}} KB</span><span>{{selectedVersion.chunkCount}} 个片段</span><span class="state-tag" :class="{ready:selectedVersion.publishStatus==='PUBLISHED'}">{{stage(selectedVersion)}}</span></div>
     <div v-if="selectedVersion.failureReason" class="banner error">{{selectedVersion.failureReason}}</div>
     <div class="actions"><button class="outline" :disabled="busy" @click="download">下载原文件</button><template v-if="admin"><button v-if="['PARSED','FAILED'].includes(selectedVersion.processStatus)" :disabled="busy||!cap.embeddingConfigured||!!cap.vectorError" @click="action('process')">{{selectedVersion.processStatus==='FAILED'?'重试向量化':'执行向量化'}}</button><button v-if="selectedVersion.processStatus==='READY'&&selectedVersion.publishStatus==='DRAFT'" :disabled="busy" @click="action('publish')">发布此版本</button><button v-if="selectedVersion.publishStatus==='PUBLISHED'" class="outline" :disabled="busy" @click="action('archive')">归档</button><button v-if="selectedVersion.publishStatus==='DRAFT'&&selectedVersion.processStatus!=='PROCESSING'" class="text-btn danger" :disabled="busy" @click="action('delete')">删除草稿</button></template></div>
     <div class="preview-heading"><b>正文预览</b><span>按实际解析片段展示 · 不改写原文</span></div>
     <div class="preview"><article v-for="c in chunks" :key="c.id" class="chunk"><small>{{c.pageNumber?'第 '+c.pageNumber+' 页':'片段 '+(c.chunkIndex+1)}}</small><pre>{{c.content}}</pre></article><p v-if="!chunks.length" class="muted">{{loading?'正在加载正文…':'暂无可预览片段'}}</p></div>
    </template>
   </section><section v-else class="document-detail empty"><span class="large-icon">▤</span><h2>选择一份文档</h2><p>查看版本、处理结果和原文片段。</p></section>
  </div>
  <div v-if="showUpload||showBase" class="modal-backdrop" @click.self="!busy&&(showUpload=false,showBase=false)"><section class="modal" role="dialog" aria-modal="true" :aria-label="showBase?'新建知识库':'上传文档'"><button class="modal-close" @click="showUpload=false;showBase=false" :disabled="busy" aria-label="关闭">×</button>
   <form v-if="showUpload" @submit.prevent="upload"><span class="eyebrow">DOCUMENT INGESTION</span><h2>{{uploadTarget?'上传新版本':'添加技术资料'}}</h2><p class="muted">PDF / DOCX / Markdown / TXT，单文件不超过 20 MB。</p><label>选择文件<input type="file" required accept=".pdf,.docx,.md,.markdown,.txt" @change="pickFile"></label><label>文档标题<input v-model="title" required maxlength="200" :readonly="!!uploadTarget"></label><label>版本号<input v-model="versionNo" required maxlength="30" placeholder="例如 v2.0"></label><p v-if="error" class="error" role="alert">{{error}}</p><button :disabled="busy">{{busy?'正在上传与解析…':'上传并解析'}}</button><small>文本文件使用 UTF-8；扫描 PDF 暂不支持。</small></form>
   <form v-else @submit.prevent="createBase"><span class="eyebrow">NEW KNOWLEDGE BASE</span><h2>创建知识库</h2><label>名称<input v-model="baseName" required maxlength="100"></label><label>用途说明<textarea v-model="baseDescription" maxlength="500" rows="3"></textarea></label><p v-if="error" class="error">{{error}}</p><button :disabled="busy">创建知识库</button></form>
  </section></div>
 </section>
</template>
