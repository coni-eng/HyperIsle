import os
import re

def fix_file_structure(filepath):
    if not os.path.exists(filepath):
        return

    with open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    package_line = None
    imports = set()
    other_lines = []
    header_comments = []
    
    # 1. Parse
    package_found = False
    
    for line in lines:
        stripped = line.strip()
        
        if stripped.startswith("package "):
            package_line = line
            package_found = True
            continue
            
        if stripped.startswith("import "):
            imports.add(stripped) # Store clean import string
            continue
            
        if not package_found and (stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*") or not stripped):
            # Likely header comments or empty lines before package
            header_comments.append(line)
        else:
            other_lines.append(line)

    if not package_line:
        # If no package line, just return (maybe script or snippet?)
        return

    # 2. Fix content (Log -> HiLog)
    # Join other_lines to process content
    body = "".join(other_lines)
    
    # Replace Log calls
    # Handle HiHiLog first to avoid confusion
    body = body.replace("HiHiLog", "HiLog")
    
    # Regex for Log.d(TAG, ...) -> HiLog.d(HiLog.TAG_ISLAND, ...)
    # But we need to handle different tags.
    # Default tag map
    tag_map = {
        '"HyperIsleIsland"': 'HiLog.TAG_ISLAND',
        '"HyperIsle"': 'HiLog.TAG_ISLAND',
        '"HyperIsleAnchor"': '"HyperIsleAnchor"',
        '"HI_NOTIF"': 'HiLog.TAG_NOTIF',
        '"HI_CALL"': 'HiLog.TAG_CALL',
        '"HI_INPUT"': 'HiLog.TAG_INPUT',
        '"HI_WHATSAPP"': '"HI_WHATSAPP"',
        'TAG': 'HiLog.TAG_ISLAND' # Default for generic TAG
    }
    
    def log_replacer(match):
        level = match.group(1) # d, i, w, e, v
        args = match.group(2) # content inside parens
        
        # Parse first arg (tag)
        # Split by comma, respecting quotes? Simple split for now
        parts = args.split(',', 1)
        if len(parts) < 2:
            return match.group(0) # Don't touch if weird format
            
        tag = parts[0].strip()
        rest = parts[1]
        
        new_tag = tag_map.get(tag, tag) # Use mapped or original
        if tag == 'TAG':
             # Try to be smarter? No, default is fine.
             pass
             
        return f"HiLog.{level}({new_tag}, {rest})"

    # Regex: Log.(d|i|w|e|v) ( ... )
    # Note: capturing balanced parens is hard in regex.
    # We assume standard one-line or simple multi-line Log calls.
    # But some are multi-line.
    # Let's simple string replace first for known tags.
    
    # Simple replaces for known patterns
    body = body.replace('Log.d(TAG,', 'HiLog.d(HiLog.TAG_ISLAND,')
    body = body.replace('Log.i(TAG,', 'HiLog.i(HiLog.TAG_ISLAND,')
    body = body.replace('Log.w(TAG,', 'HiLog.w(HiLog.TAG_ISLAND,')
    body = body.replace('Log.e(TAG,', 'HiLog.e(HiLog.TAG_ISLAND,')
    body = body.replace('Log.d("HyperIsleIsland",', 'HiLog.d(HiLog.TAG_ISLAND,')
    body = body.replace('Log.w("HyperIsleIsland",', 'HiLog.w(HiLog.TAG_ISLAND,')
    body = body.replace('Log.e("HyperIsleIsland",', 'HiLog.e(HiLog.TAG_ISLAND,')
    body = body.replace('Log.d("HyperIsle",', 'HiLog.d(HiLog.TAG_ISLAND,')
    body = body.replace('Log.w("HyperIsle",', 'HiLog.w(HiLog.TAG_ISLAND,')
    body = body.replace('Log.d("HI_NOTIF",', 'HiLog.d(HiLog.TAG_NOTIF,')
    body = body.replace('Log.d("HI_CALL",', 'HiLog.d(HiLog.TAG_CALL,')
    body = body.replace('Log.d("HI_INPUT",', 'HiLog.d(HiLog.TAG_INPUT,')
    
    # Catch-all for remaining Log.d
    # If imports are cleaned, Log.d will fail.
    # We must ensure HiLog import is present if we use it.
    
    # 3. Reconstruct imports
    # Remove android.util.Log
    if "import android.util.Log" in imports:
        imports.remove("import android.util.Log")
    
    # Add HiLog if missing and needed
    if "HiLog." in body and "import com.coni.hyperisle.util.HiLog" not in imports:
        imports.add("import com.coni.hyperisle.util.HiLog")
        
    sorted_imports = sorted(list(imports))
    
    # 4. Write back
    with open(filepath, 'w', encoding='utf-8') as f:
        # Header comments
        f.writelines(header_comments)
        # Package
        f.write(package_line) # Has \n
        if not package_line.endswith('\n'): f.write('\n')
        f.write('\n')
        # Imports
        for imp in sorted_imports:
            f.write(imp + '\n')
        f.write('\n')
        # Body
        f.write(body)
        
    print(f"Fixed {filepath}")

# Apply to all kotlin files
for root, dirs, files in os.walk(r"c:\Users\bekir\HyperIsle\app\src\main\java"):
    for file in files:
        if file.endswith(".kt"):
            fix_file_structure(os.path.join(root, file))
