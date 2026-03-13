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
var activeImageEl = null;

function clearActiveImageSelection() {
    if (activeImageEl) {
        activeImageEl.classList.remove('editor-image-selected');
        activeImageEl = null;
    }
}

function setActiveImage(imageEl) {
    clearActiveImageSelection();
    if (!imageEl) return;
    activeImageEl = imageEl;
    activeImageEl.classList.add('editor-image-selected');
}

function bindImageSelection(container) {
    if (!container) return;
    container.addEventListener('click', function (e) {
        if (e.target && e.target.tagName === 'IMG') {
            setActiveImage(e.target);
        } else {
            clearActiveImageSelection();
        }
    });
}

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

function insertImageRawEditor(url) {
    var html = '<p><img src="' + url + '" alt="Image" style="width:60%;height:auto;" /></p>';
    rawEditor.focus();
    var sel = window.getSelection();
    if (sel && sel.rangeCount > 0) {
        var range = sel.getRangeAt(0);
        range.deleteContents();
        var wrapper = document.createElement('div');
        wrapper.innerHTML = html;
        var frag = document.createDocumentFragment();
        var node;
        var lastNode = null;
        while ((node = wrapper.firstChild)) {
            lastNode = frag.appendChild(node);
        }
        range.insertNode(frag);
        if (lastNode) {
            range.setStartAfter(lastNode);
            range.collapse(true);
            sel.removeAllRanges();
            sel.addRange(range);
        }
    } else {
        rawEditor.insertAdjacentHTML('beforeend', html);
    }
    var images = rawEditor.querySelectorAll('img');
    if (images.length) {
        setActiveImage(images[images.length - 1]);
    }
}

function insertImageIntoEditor(url) {
    if (useRawConvoEditor && rawEditor) {
        insertImageRawEditor(url);
        scheduleSave();
        return;
    }
    var range = quill.getSelection(true);
    var index = range ? range.index : quill.getLength();
    quill.insertEmbed(index, 'image', url, 'user');
    var images = quill.root.querySelectorAll('img');
    if (images.length) {
        var inserted = images[images.length - 1];
        inserted.style.width = '60%';
        inserted.style.height = 'auto';
        setActiveImage(inserted);
    }
    quill.setSelection(index + 1, 0, 'user');
    scheduleSave();
}

function resizeSelectedImage() {
    if (!activeImageEl) {
        setStatus('click an image first', 'unsaved');
        return;
    }
    var current = activeImageEl.style.width || '60%';
    var next = window.prompt('Image width (e.g. 320px, 60%, auto)', current);
    if (next === null) return;
    var width = next.trim();
    if (!width || width.toLowerCase() === 'auto') {
        activeImageEl.style.width = '';
    } else {
        activeImageEl.style.width = width;
    }
    activeImageEl.style.height = 'auto';
    scheduleSave();
}

function triggerImageUpload() {
    var picker = document.createElement('input');
    picker.type = 'file';
    picker.accept = 'image/*';
    picker.onchange = function () {
        if (!picker.files || !picker.files.length) return;
        var file = picker.files[0];
        var formData = new FormData();
        var tokenInput = document.querySelector('input[name="__anti-forgery-token"]');
        if (tokenInput) {
            formData.append('__anti-forgery-token', tokenInput.value);
        }
        formData.append('image', file);
        setStatus('uploading image...', 'saving');
        fetch('/editor/upload-image', { method: 'POST', body: formData })
            .then(function (res) {
                if (!res.ok) throw new Error('upload failed');
                return res.json();
            })
            .then(function (payload) {
                if (!payload || !payload.url) throw new Error('missing url');
                insertImageIntoEditor(payload.url);
                setStatus('saved');
            })
            .catch(function () {
                setStatus('image upload failed', 'unsaved');
            });
    };
    picker.click();
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
    bindImageSelection(rawEditor);
    rawEditor.addEventListener('input', scheduleSave);
} else {
    bindImageSelection(quill.root);
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
