import { figure, createTheme } from "kineglyph";
import themeOptions from "./theme.mjs";
export const theme = createTheme(themeOptions);
const stages=[
 ["join","01","Join the server","The mod and plugin exchange their capabilities.","signal","info"],
 ["identity","02","Get its identity","The server supplies a signed community identity.","key","accent"],
 ["verify","03","Check with Schematio","The mod verifies the server's signed response.","graph","info"],
 ["ready","04","Use server tools","Server clipboard actions become available.","blocks","success"]
];
export default figure("bridge-detection",{title:"The bridge detects your server",description:"The join handshake connects detection to verified server clipboard actions."},f=>{
 const cards=stages.map(([id,number,title,body,motif,tone])=>f.card({id,eyebrow:number,title,body,motif,tone,compact:true,label:title}));
 const note=f.body("The mod checks the connection when you join. Keep the community token on the server. WorldEdit and the corresponding permissions are required for clipboard actions.");
 f.root(f.stack([f.flow(cards,{gap:{wide:32,compact:22,narrow:22},width:"fill"}),f.stack([f.eyebrow("AUTOMATIC ON JOIN",{tone:"accent"}),note],{gap:8,padding:16,width:"fill",frame:{fill:"surface",stroke:"border",radius:10}})],{gap:24,padding:24,width:"fill"}));
 const edges=cards.slice(0,-1).map((card,i)=>f.connect(card,cards[i+1],{head:"arrow",tone:"connector"}));
});
