import { figure, createTheme } from "kineglyph";
import themeOptions from "./theme.mjs";
export const theme = createTheme(themeOptions);
export default figure("community-link",{title:"Link a server to your community",description:"A community administrator creates a plugin token and installs it on the Paper server. Players retain their own identities and permissions."},f=>{
 const owner=f.card({id:"owner",eyebrow:"COMMUNITY ADMIN",title:"Create a plugin token",body:"Community settings → Plugin API Tokens",motif:"key",tone:"accent"});
 const server=f.card({id:"server",eyebrow:"SERVER CONSOLE",title:"Set the token",body:"schematio settoken <token>",motif:"code",tone:"info"});
 const result=f.card({id:"result",eyebrow:"LINKED COMMUNITY",title:"Share the workshop",body:"Uploads belong to the community and credit the player",motif:"layers",tone:"success"});
 f.root(f.stack([f.flow([owner,server,result],{gap:{wide:48,compact:24,narrow:22},width:"fill"}),f.caption("The token stays on the server. Players do not need a copy of it.",{tone:"textMuted"})],{gap:24,padding:24,width:"fill"}));
 const a=f.connect(owner,server,{head:"arrow",tone:"accent"});const b=f.connect(server,result,{head:"arrow",tone:"info"});
});
