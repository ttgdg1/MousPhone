"""Read Windows keyboard DLL tables without loading/activating an input locale.

Requires 64-bit Windows/Python. It calls only each OS DLL's KbdLayerDescriptor
export, then reads its static KBDTABLES. It never calls LoadKeyboardLayout,
ActivateKeyboardLayout, ToUnicodeEx, SendInput or SetKeyboardState.
"""
from pathlib import Path
import ctypes as C
import hashlib
import json
import os
import sys

if os.name != 'nt' or C.sizeof(C.c_void_p) != 8:
    raise SystemExit('Run with 64-bit Python on Windows.')

BYTE, WORD, DWORD, PTR = C.c_uint8, C.c_uint16, C.c_uint32, C.c_void_p

class Modifiers(C.Structure):
    _fields_ = [('pVkToBit', PTR), ('wMaxModBits', WORD), ('ModNumber', BYTE * 16)]

class WcharTable(C.Structure):
    _fields_ = [('pVkToWchars', PTR), ('nModifications', BYTE), ('cbSize', BYTE)]

class DeadKey(C.Structure):
    _fields_ = [('dwBoth', DWORD), ('wcComposed', WORD), ('uFlags', WORD)]

class KbdTables(C.Structure):
    _fields_ = [('pCharModifiers', PTR), ('pVkToWcharTable', PTR), ('pDeadKey', PTR),
                ('pKeyNames', PTR), ('pKeyNamesExt', PTR), ('pKeyNamesDead', PTR),
                ('pusVSCtoVK', PTR), ('bMaxVSCtoVK', BYTE), ('pVSCtoVK_E0', PTR),
                ('pVSCtoVK_E1', PTR), ('fLocaleFlags', DWORD), ('nLgMax', BYTE),
                ('cbLgEntry', BYTE), ('pLigature', PTR), ('dwType', DWORD), ('dwSubType', DWORD)]

# PC/AT Set-1 scan codes to USB HID Keyboard/Keypad usages. Only text keys.
SC_TO_HID = dict(zip(
    [0x1e,0x30,0x2e,0x20,0x12,0x21,0x22,0x23,0x17,0x24,0x25,0x26,0x32,
     0x31,0x18,0x19,0x10,0x13,0x1f,0x14,0x16,0x2f,0x11,0x2d,0x15,0x2c], range(4,30)))
SC_TO_HID.update(dict(zip(range(0x02,0x0c), range(0x1e,0x28))))
SC_TO_HID.update({0x1c:0x28,0x0f:0x2b,0x39:0x2c,0x0c:0x2d,0x0d:0x2e,
                  0x1a:0x2f,0x1b:0x30,0x2b:0x31,0x27:0x33,0x28:0x34,
                  0x29:0x35,0x33:0x36,0x34:0x37,0x35:0x38,0x56:0x64})

LAYOUTS = [('US','00000409','kbdus.dll'),('RU','00000419','kbdru.dll'),
           ('UK','00000809','kbduk.dll'),('DE','00000407','kbdgr.dll'),
           ('FR','0000040C','kbdfr.dll'),('ES','0000040A','kbdsp.dll')]

def read_layout(name, klid, dll_name):
    path = Path(os.environ['SystemRoot']) / 'System32' / dll_name
    dll = C.WinDLL(str(path))
    entry = dll.KbdLayerDescriptor
    entry.restype = C.POINTER(KbdTables)
    table = entry().contents
    modifiers = Modifiers.from_address(table.pCharModifiers)
    scans = C.cast(table.pusVSCtoVK, C.POINTER(WORD))
    rows = []
    for i in range(32):
        sub = WcharTable.from_address(table.pVkToWcharTable + i*C.sizeof(WcharTable))
        if not sub.pVkToWchars: break
        assert sub.cbSize == 2 + 2*sub.nModifications, (name, sub.cbSize, sub.nModifications)
        for n in range(256):
            address = sub.pVkToWchars + n*sub.cbSize
            vk = BYTE.from_address(address).value
            if vk == 0: break
            values = list((WORD*sub.nModifications).from_address(address+2))
            next_values = list((WORD*sub.nModifications).from_address(address+sub.cbSize+2))
            rows.append((vk, values, next_values))
    direct, dead, decoded = {}, [], []
    for mod_bits, hid_mod in [(0,0),(1,2),(6,64),(7,66)]:
        if mod_bits > modifiers.wMaxModBits: continue
        column = modifiers.ModNumber[mod_bits]
        if column == 15: continue
        for sc, hid in sorted(SC_TO_HID.items(), key=lambda pair:pair[1]):
            if sc >= table.bMaxVSCtoVK: continue
            vk = scans[sc] & 0xff
            if vk in (0,0xff): continue
            for row_vk, values, dead_values in rows:
                if row_vk != vk or column >= len(values): continue
                value = values[column]
                if value == 0xf001:
                    accent = dead_values[column]
                    dead.append((accent, hid, hid_mod))
                    decoded.append({'key':hid,'modifiers':hid_mod,'dead':accent})
                elif value < 0xf000 and (value >= 0x20 or value in (9,13)):
                    direct.setdefault(value, [(hid,hid_mod)])
                    decoded.append({'key':hid,'modifiers':hid_mod,'character':value})
                break
    # Text mode uses the same Enter report for CR and LF.
    direct[10] = [(0x28,0)]
    direct[13] = [(0x28,0)]
    # No raw dead-key stroke is ever exposed: even a literal accent must finish.
    sequences = dict(direct)
    dead_entries = []
    if table.pDeadKey:
        for i in range(2048):
            item = DeadKey.from_address(table.pDeadKey + i*C.sizeof(DeadKey))
            if not item.dwBoth: break
            base, accent = item.dwBoth & 0xffff, item.dwBoth >> 16
            dead_entries.append({'base':base,'accent':accent,'composed':item.wcComposed,'flags':item.uFlags})
            if item.uFlags != 0 or base not in direct: continue
            for dead_accent, key, mod in dead:
                if accent == dead_accent:
                    sequences.setdefault(item.wcComposed, [(key,mod)] + direct[base])
                    break
    return {'layout':name,'klid':klid,'dll':dll_name,
            'dll_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'characters':{f'{code:04X}': sequence for code,sequence in sorted(sequences.items())},
            'physical_keys':decoded,'dead_transitions':dead_entries}

def main():
    results = [read_layout(*layout) for layout in LAYOUTS]
    root = Path(__file__).resolve().parents[2]
    dest = root/'tests'/'fixtures'/'windows-layouts.json'
    dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_text(json.dumps({'source':'Installed Microsoft Windows keyboard DLL KBDTABLES; Caps Lock off',
                                'generator':'tools/layouts/extract_windows_layouts.py',
                                'layouts':results},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    # App inverse lookup; tests use separate forward key/dead-composition tables.
    source = root/'app/src/main/java/io/pocketpad/remote/WindowsLayoutData.java'
    java = ['package io.pocketpad.remote;', '',
            '/** Generated from Microsoft Windows keyboard DLL KBDTABLES. See tools/layouts/README.md. */',
            'final class WindowsLayoutData {', '    static String forLayout(KeyboardMapper.HostLayout layout) {',
            '        switch (layout) {']
    tsv = ['# Microsoft Windows layout tables; Caps Lock off. KEY columns: layout/key/modifiers/character/dead.',
           '# COMPOSE columns: layout/accent/base/composed/flags. Values are hexadecimal.']
    for data in results:
        entries = []
        for ch, seq in data['characters'].items():
            entries.append(ch+'='+','.join(f'{key:02X}{mod:02X}' for key,mod in seq)+';')
        chunks = [''.join(entries[i:i+8]) for i in range(0,len(entries),8)]
        java.append('            case '+data['layout']+': return')
        for i,chunk in enumerate(chunks):
            java.append('                "'+chunk+'"'+(';' if i==len(chunks)-1 else ' +'))
        for event in data['physical_keys']:
            tsv.append(f"{data['layout']}\tKEY\t{event['key']:02X}\t{event['modifiers']:02X}\t{event.get('character',event.get('dead')):04X}\t{int('dead' in event)}")
        for event in data['dead_transitions']:
            tsv.append(f"{data['layout']}\tCOMPOSE\t{event['accent']:04X}\t{event['base']:04X}\t{event['composed']:04X}\t{event['flags']:04X}")
    java += ['            default: throw new IllegalArgumentException("Unknown host layout");',
             '        }', '    }', '    private WindowsLayoutData() {}', '}']
    source.write_text('\n'.join(java)+'\n',encoding='utf-8')
    (dest.parent/'windows-key-events.tsv').write_text('\n'.join(tsv)+'\n',encoding='utf-8')
    for data in results:
        sequences = data['characters']
        samples = {ch: sequences.get(f'{ord(ch):04X}') for ch in 'aAzZéÉèÈàÀçÇùÙäÄöÖüÜßñÑáÁíÍóÓúÚ¡¿£€^`~\\|'}
        print(data['layout'], len(sequences), json.dumps(samples,ensure_ascii=True))
    print(dest)

if __name__ == '__main__': main()
