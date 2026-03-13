// Quill and DOM are both ready since this script loads last in the body

var quillToolbarOptions = [
    [{ header: [2, 3, false] }],
    ['bold', 'italic'],
    ['code-block'],
    ['link'],
    [{ list: 'ordered' }, { list: 'bullet' }],
    ['blockquote'],
    ['clean']
];

function createQuill(selector) {
    return new Quill(selector, {
        theme: 'snow',
        modules: {
            toolbar: quillToolbarOptions
        }
    });
}

function splitConvoContent(html) {
    var wrapper = document.createElement('div');
    wrapper.innerHTML = html;
    var transcript = wrapper.querySelector('.convo-transcript');
    if (!transcript) {
        return null;
    }

    var intro = document.createElement('div');
    var outro = document.createElement('div');
    var beforeTranscript = true;
    var child = wrapper.firstChild;

    while (child) {
        var next = child.nextSibling;
        if (child === transcript) {
            beforeTranscript = false;
        } else if (beforeTranscript) {
            intro.appendChild(child);
        } else {
            outro.appendChild(child);
        }
        child = next;
    }

    return {
        intro: intro.innerHTML,
        transcript: transcript.outerHTML,
        outro: outro.innerHTML
    };
}

var initial = document.getElementById('initial-content');
var editorHost = document.getElementById('editor');
var convoEditorShell = document.getElementById('convo-editor-shell');
var transcriptEditor = document.getElementById('editor-transcript');
var singleQuill = createQuill('#editor');
var introQuill = null;
var outroQuill = null;
var useSegmentedConvoEditor = false;
var activeEditorSurface = 'single';

function setActiveEditorSurface(surface) {
    activeEditorSurface = surface;
}

if (initial && initial.innerHTML.trim()) {
    var convoParts = splitConvoContent(initial.innerHTML);
    useSegmentedConvoEditor = !!convoParts;

    if (useSegmentedConvoEditor) {
        var singleToolbar = editorHost.previousElementSibling;
        if (singleToolbar && singleToolbar.classList && singleToolbar.classList.contains('ql-toolbar')) {
            singleToolbar.style.display = 'none';
        }
        editorHost.style.display = 'none';
        convoEditorShell.style.display = 'block';
        introQuill = createQuill('#editor-intro');
        outroQuill = createQuill('#editor-outro');
        introQuill.root.innerHTML = convoParts.intro;
        outroQuill.root.innerHTML = convoParts.outro;
        transcriptEditor.innerHTML = convoParts.transcript;
        setActiveEditorSurface('transcript');
    } else {
        singleQuill.root.innerHTML = initial.innerHTML;
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
    var contentValue;
    if (useSegmentedConvoEditor) {
        contentValue = introQuill.root.innerHTML + transcriptEditor.innerHTML + outroQuill.root.innerHTML;
    } else {
        contentValue = singleQuill.root.innerHTML;
    }
    document.querySelector('input[name="content"]').value  = contentValue;
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
    transcriptEditor.focus();
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
        transcriptEditor.insertAdjacentHTML('beforeend', html);
    }
    var images = transcriptEditor.querySelectorAll('img');
    if (images.length) {
        setActiveImage(images[images.length - 1]);
    }
}

function insertImageIntoQuill(quill, url) {
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
}

function insertImageIntoEditor(url) {
    if (useSegmentedConvoEditor && activeEditorSurface === 'transcript') {
        insertImageRawEditor(url);
        scheduleSave();
        return;
    }

    var targetQuill = singleQuill;
    if (useSegmentedConvoEditor) {
        targetQuill = activeEditorSurface === 'outro' ? outroQuill : introQuill;
    }

    insertImageIntoQuill(targetQuill, url);
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
if (useSegmentedConvoEditor) {
    bindImageSelection(transcriptEditor);
    bindImageSelection(introQuill.root);
    bindImageSelection(outroQuill.root);
    transcriptEditor.addEventListener('focus', function () {
        setActiveEditorSurface('transcript');
    });
    transcriptEditor.addEventListener('click', function () {
        setActiveEditorSurface('transcript');
    });
    transcriptEditor.addEventListener('input', scheduleSave);
    introQuill.root.addEventListener('focus', function () {
        setActiveEditorSurface('intro');
    });
    introQuill.root.addEventListener('click', function () {
        setActiveEditorSurface('intro');
    });
    outroQuill.root.addEventListener('focus', function () {
        setActiveEditorSurface('outro');
    });
    outroQuill.root.addEventListener('click', function () {
        setActiveEditorSurface('outro');
    });
    introQuill.on('text-change', scheduleSave);
    outroQuill.on('text-change', scheduleSave);
} else {
    bindImageSelection(singleQuill.root);
    singleQuill.root.addEventListener('focus', function () {
        setActiveEditorSurface('single');
    });
    singleQuill.root.addEventListener('click', function () {
        setActiveEditorSurface('single');
    });
    singleQuill.on('text-change', scheduleSave);
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
