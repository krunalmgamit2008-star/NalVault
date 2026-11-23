const fs = require('fs');
const P = 'app/src/main/java/com/kavach/vault/VaultScreen.kt';
let s = fs.readFileSync(P, 'utf8');
let n = 0;
function r(a, b) {
  if (s.indexOf(a) < 0) { console.log('SKIP ' + a.slice(0, 40)); return; }
  s = s.replace(a, b); n++;
}
r('    val colour: Color,\n    val onClick: () -> Unit\n)', '    val colour: Color,\n    val count: Int = -1,\n    val onClick: () -> Unit\n)');
r('Color(0xFF30D158), onCamera)', 'Color(0xFF30D158), 0, onCamera)');
r('Color(0xFF0A84FF)) { onCategory("Photos") }', 'Color(0xFF0A84FF), items.count { categoryOf(it.name) == "Photos" }) { onCategory("Photos") }');
r('Color(0xFFBF5AF2)) { onCategory("Videos") }', 'Color(0xFFBF5AF2), items.count { categoryOf(it.name) == "Videos" }) { onCategory("Videos") }');
r('Color(0xFFE0A800)) { onCategory("Audio") }', 'Color(0xFFE0A800), items.count { categoryOf(it.name) == "Audio" }) { onCategory("Audio") }');
r('    tiles.add(HomeTile("My files", Icons.Filled.Folder, Color(0xFF32ADE6), onFiles))', '    tiles.add(HomeTile("Documents", Icons.Filled.Description, Color(0xFF32ADE6), items.count { categoryOf(it.name) == "Documents" }) { onCategory("Documents") })\n    tiles.add(HomeTile("My files", Icons.Filled.Folder, Color(0xFF32ADE6), items.size, onFiles))');
r('Color(0xFFFF8FA3), onSettings)', 'Color(0xFFFF8FA3), -1, onSettings)');
r('        tiles.add(HomeTile(name, Icons.Filled.Folder, Color(0xFF5E5CE6)) { onOpenFolder(name) })', '        tiles.add(HomeTile(name, Icons.Filled.Folder, Color(0xFF5E5CE6), items.count { it.folder == name }) { onOpenFolder(name) })');
r('                        Pill(dateText)\n                    }\n                }', '                        Pill(dateText)\n                    }\n                    Spacer(Modifier.weight(1f))\n                    IconButton(onClick = onSettings) {\n                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)\n                    }\n                }');
r('                                    color = Color.White,\n                                    maxLines = 1\n                                )', '                                    color = Color.White,\n                                    maxLines = 1\n                                )\n                                if (tile.count >= 0) {\n                                    Text("${tile.count} ${tile.label}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f), maxLines = 1)\n                                }');
fs.writeFileSync(P, s);
console.log('applied ' + n + ' of 10');
