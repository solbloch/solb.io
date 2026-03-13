// Quill and DOM are both ready since this script loads last in the body

var quill = new Quill('#editor', {
    theme: 'snow',
    modules: {
        toolbar: [
            [{ header: [2, 3, false] }],
            ['bold', 'italic'],
            ['code-block'],
            ['link'],
            [{ list: 'ordered' }, { list: 'bullet' }],
            ['blockquote'],
            ['clean']
        ]
    }
});

// Load existing post HTML into Quill
var initial = document.getElementById('initial-content');
var editorHost = document.getElementById('editor');
var useRawConvoEditor = false;
var rawEditor = null;

if (initial && initial.innerHTML.trim()) {
    useRawConvoEditor = initial.innerHTML.indexOf('convo-transcript') !== -1;
    if (useRawConvoEditor) {
        var toolbar = editorHost.previousElementSibling;
        if (toolbar && toolbar.classList && toolbar.classList.contains('ql-toolbar')) {
            toolbar.style.display = 'none';
        }
        editorHost.style.display = 'none';
        rawEditor = document.createElement('div');
        rawEditor.id = 'raw-convo-editor';
        rawEditor.className = 'raw-convo-editor content';
        rawEditor.setAttribute('contenteditable', 'true');
        rawEditor.setAttribute('spellcheck', 'false');
        rawEditor.innerHTML = initial.innerHTML;
        editorHost.parentNode.insertBefore(rawEditor, editorHost.nextSibling);
    } else {
        quill.root.innerHTML = initial.innerHTML;
    }
}

// ── Save state ──────────────────────────────────────────────
var saveTimer = null;

function setStatus(msg, cls) {
    var el = document.getElementById('save-status');
    el.textContent = msg;
    el.className = 'save-status' + (cls ? ' ' + cls : '');
}

function collectAll() {
    document.querySelector('input[name="content"]').value  = useRawConvoEditor && rawEditor ? rawEditor.innerHTML : quill.root.innerHTML;
    document.querySelector('input[name="title"]').value    = document.getElementById('post-title').innerText.trim();
    document.querySelector('input[name="tags"]').value     = document.getElementById('post-tags').innerText.trim();
    document.querySelector('input[name="forward"]').value  = document.getElementById('post-forward').innerText.trim();
}

function doSave() {
    setStatus('saving...', 'saving');
    collectAll();
    var token = document.querySelector('input[name="__anti-forgery-token"]').value;
    var id    = document.querySelector('input[name="id"]').value;
    var formData = new FormData();
    formData.append('__anti-forgery-token', token);
    formData.append('id',      id);
    formData.append('content', document.querySelector('input[name="content"]').value);
    formData.append('title',   document.querySelector('input[name="title"]').value);
    formData.append('tags',    document.querySelector('input[name="tags"]').value);
    formData.append('forward', document.querySelector('input[name="forward"]').value);
    fetch('/autosave', { method: 'POST', body: formData })
        .then(function(res) {
            setStatus(res.ok ? 'saved' : 'save failed!', res.ok ? '' : 'unsaved');
        })
        .catch(function() {
            setStatus('save failed!', 'unsaved');
        });
}

function scheduleSave() {
    setStatus('unsaved', 'unsaved');
    clearTimeout(saveTimer);
    saveTimer = setTimeout(doSave, 2000);
}

// ── Public actions wired to toolbar buttons ─────────────────
function manualSave() {
    clearTimeout(saveTimer);
    doSave();
}

function saveAndReload() {
    clearTimeout(saveTimer);
    collectAll();
    document.getElementById('save-form').submit();
}

// ── Change listeners ────────────────────────────────────────
if (useRawConvoEditor && rawEditor) {
    rawEditor.addEventListener('input', scheduleSave);
} else {
    quill.on('text-change', scheduleSave);
}

['post-title', 'post-tags', 'post-forward'].forEach(function(id) {
    document.getElementById(id).addEventListener('input', scheduleSave);
});

// Ctrl+S → immediate save
document.addEventListener('keydown', function(e) {
    if (e.ctrlKey && e.key === 's') {
        e.preventDefault();
        clearTimeout(saveTimer);
        doSave();
    }
});
