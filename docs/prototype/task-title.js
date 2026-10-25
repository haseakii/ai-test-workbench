/* Offline, allowlisted title suggestions; no model call, attachment read or raw-text truncation. */
(() => {
  'use strict';
  const normalize=s=>String(s).replace(/\s+/gu,' ').trim();
  const length=s=>[...new Intl.Segmenter('zh',{granularity:'grapheme'}).segment(s)].length;
  function validate(value){
    const title=normalize(value);
    if(!title)return {error:'请填写任务名称。'};
    if(length(title)>40)return {error:'任务名称最多 40 个字或可见字符。'};
    if(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u200b-\u200f\u202a-\u202e\u2066-\u2069]/u.test(title))return {error:'任务名称不能包含隐藏控制字符。'};
    if(/\b\d{11,}\b|[\w.+-]+@[\w.-]+\.[a-z]{2,}|https?:\/\/|(?:密码|令牌|token|secret|api[_ -]?key)\s*[:=：]|\bBearer\s+\S+/iu.test(title))return {error:'请移除标题中的联系方式、链接或凭据，改用问题描述。'};
    return {title};
  }
  function suggest(s){
    const inputs=s.messages.filter(m=>m.role==='user'),text=inputs.map(m=>m.text).join(' ');
    const region=['德国','法国','英国','日本','华东','华南','华北'].find(x=>text.includes(x))||'';
    if(/报表|明细/.test(text)){
      const specific=/金额|差异|不一致|对不上|多了|少了/.test(text);
      return {title:region+(/订单/.test(text)?'订单':'')+(specific?(/金额|EUR|CNY|人民币/.test(text)?'报表金额差异':'报表差异核对'):'报表与明细核对'),needsRefinement:!specific};
    }
    if(/接口|回调/.test(text))return {title:region+(/订单/.test(text)?'订单':'')+(/超时/.test(text)?'接口超时排查':'接口异常排查'),needsRefinement:!(/超时|错误|异常|失败/.test(text))};
    if(/日志/.test(text))return {title:'错误日志分析',needsRefinement:false};
    return {title:inputs.some(m=>m.files?.length)?'附件问题待补充':'问题待补充',needsRefinement:true};
  }
  function assign(s,title,source,extra={}){
    const previous=s.title;s.title=title;
    s.titleMeta={...s.titleMeta,...extra,source,revision:(s.titleMeta?.revision||0)+1};
    s.titleHistory??=[];s.titleHistory.push({revision:s.titleMeta.revision,previous,title,source,actor:source==='manual'?s.subjectId:'offline-title-rule',at:new Date().toISOString(),synthetic:true});
  }
  function auto(s){
    const meta=s.titleMeta;if(meta?.source==='manual'||meta?.refinementUsed||meta?.source==='automatic'&&!meta.needsRefinement)return;
    const candidate=suggest(s);
    if(!meta||meta.source==='placeholder')assign(s,candidate.title,'automatic',{needsRefinement:candidate.needsRefinement,refinementUsed:false});
    else if(candidate.title!==s.title&&!candidate.needsRefinement)assign(s,candidate.title,'automatic',{needsRefinement:false,refinementUsed:true});
  }
  function rename(s,value,expectedRevision){
    if(expectedRevision!==(s.titleMeta?.revision||0))return {error:'标题已更新，请取消后重新打开，确认最新名称。'};
    const result=validate(value);if(result.error)return result;
    if(result.title!==s.title||s.titleMeta?.source!=='manual')assign(s,result.title,'manual',{needsRefinement:false});
    return {ok:true};
  }
  window.TaskTitle={auto,rename,validate};
})();
