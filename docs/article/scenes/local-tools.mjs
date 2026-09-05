import { figure, createTheme } from "kineglyph";
import themeOptions from "./theme.mjs";
export const theme = createTheme(themeOptions);
export default figure("local-tools",{title:"One browser, two local building tools",description:"The Fabric mod loads Litematica placements on your client, or a WorldEdit clipboard in singleplayer and as LAN host."},f=>{
 const source=f.card({id:"source",eyebrow:"SCHEMAT.IO",title:"Find a schematic",body:"Browse, search and download",motif:"layers",tone:"info"});
 const mod=f.card({id:"mod",eyebrow:"FABRIC CLIENT",title:"Connector mod",body:"Choose where to load the file",motif:"signal",tone:"accent"});
 const lite=f.card({id:"litematica",eyebrow:"CLIENT PLACEMENT",title:"Litematica",body:"A hologram to build against. Works in singleplayer and multiplayer.",motif:"cube",tone:"info",compact:true});
 const we=f.card({id:"worldedit",eyebrow:"SINGLEPLAYER / LAN HOST",title:"Local WorldEdit",body:"Load your clipboard, then run //paste. Use //copy to upload.",motif:"blocks",tone:"success",compact:true});
 const destinations=f.stack([f.eyebrow("CHOOSE A DESTINATION"),lite,we],{gap:16,padding:16,width:"fill",frame:{fill:"surface",stroke:"border",radius:12}});
 f.root(f.flow([source,mod,destinations],{gap:{wide:50,compact:28,narrow:24},width:"fill",padding:24}));
 const a=f.connect(source,mod,{head:"arrow",tone:"info"});
 const b=f.connect(mod,destinations,{head:"arrow",tone:"info"});
});
