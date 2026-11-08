# Hype-lite - note
Comenzi: /atm [deposit|withdraw <suma> | transfer <jucator> <suma>], /job [select <id>|leave], /sete, /hud, /hype atm set|remove|list, /hype reload.
Permisiuni: flowcity.hype.atm (default true), flowcity.hype.job (true), flowcity.hype.admin (op).
Config: plugins/FlowCityBridge/hype.yml (joburi, plati, sete, ATM). DB: hype.db.
ATM fizic: priveste un bloc si /hype atm set; click dreapta il deschide. command-anywhere=false => doar langa ATM.
Netestat pe server real (nu am putut compila): doar sintaxa javac, logica SQL pe sqlite.
Incerte: Arclight/Mohist sa transmita canalul flowcity:bridge; PotionMeta#getBasePotionData pentru apa; nume MCP la RenderGameOverlayEvent/Screen.
Nefacut: CNN, proprietati, roata, documente, meniu, ClearLagg, updater.
