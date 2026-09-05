import { figure, createTheme } from "kineglyph";
import themeOptions from "./theme.mjs";
export const theme = createTheme(themeOptions);
export default figure("server-workflow", {title:"From a community library to blocks in your world",description:"The Paper plugin downloads a schematic into your WorldEdit clipboard. Only the paste command places blocks."}, f=>{
 const library=f.card({id:"library",eyebrow:"SCHEMAT.IO",title:"Community library",body:"Shared builds, tags and authors",motif:"layers",tone:"info"});
 const plugin=f.card({id:"plugin",eyebrow:"PAPER SERVER",title:"Connector plugin",body:"Uses the community's plugin token",motif:"signal",tone:"accent"});
 const clipboard=f.card({id:"clipboard",eyebrow:"WORLDEDIT",title:"Your clipboard",body:"Loaded and ready to paste",motif:"blocks",tone:"info"});
 const world=f.card({id:"world",eyebrow:"YOUR CHOICE",title:"Place the build",body:"Run //paste when you are ready",motif:"cube",tone:"success"});
 f.root(f.stack([f.flow([library,plugin,clipboard,world],{gap:{wide:34,compact:22,narrow:22},width:"fill"}),f.caption("Upload runs the other way: select a build → //copy → /schematio upload.",{tone:"textMuted"})],{gap:24,padding:24,width:"fill"}));
 const edges=[f.connect(library,plugin,{head:"arrow",tone:"info"}),f.connect(plugin,clipboard,{head:"arrow",tone:"accent"}),f.connect(clipboard,world,{head:"arrow",tone:"success",label:"//paste"})];
});
