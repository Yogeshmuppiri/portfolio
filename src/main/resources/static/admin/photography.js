(() => {
    const byId = id => document.getElementById(id);
    const status = byId('status');
    const loginPanel = byId('login-panel');
    const uploadPanel = byId('upload-panel');
    const input = byId('photos');
    const queue = byId('queue');
    let session;
    let entries = [];
    let busy = false;

    async function checkSession() {
        const response = await fetch('/api/admin/session', { cache: 'no-store' });
        if (!response.ok) throw new Error('Unable to connect. Refresh the page to try again.');
        session = await response.json();
        loginPanel.hidden = session.authenticated;
        uploadPanel.hidden = !session.authenticated;
    }

    function headers() { return { [session.csrfHeader]: session.csrfToken }; }
    function clearQueue() {
        entries.forEach(entry => URL.revokeObjectURL(entry.preview));
        entries = [];
        queue.replaceChildren();
        input.value = '';
        byId('publish-actions').hidden = true;
    }

    byId('login-form').addEventListener('submit', async event => {
        event.preventDefault();
        const button = event.submitter;
        button.disabled = true;
        status.textContent = 'Signing in...';
        try {
            await checkSession();
            const response = await fetch('/api/admin/login', {
                method: 'POST', headers: headers(),
                body: new URLSearchParams(new FormData(event.target))
            });
            byId('password').value = '';
            if (!response.ok) throw new Error('Sign-in failed. Check your username and password.');
            await checkSession();
            status.textContent = 'Choose photos to publish.';
        } catch (error) { status.textContent = error.message; }
        finally { button.disabled = false; }
    });

    byId('logout').addEventListener('click', async () => {
        if (busy) return;
        try {
            await checkSession();
            const response = await fetch('/api/admin/logout', { method: 'POST', headers: headers() });
            if (!response.ok) throw new Error('Could not sign out. Please try again.');
            clearQueue();
            await checkSession();
            status.textContent = 'Signed out.';
        } catch (error) { status.textContent = error.message; }
    });

    input.addEventListener('change', () => {
        const files = Array.from(input.files);
        entries.forEach(entry => URL.revokeObjectURL(entry.preview));
        entries = [];
        queue.replaceChildren();
        byId('publish-actions').hidden = true;
        if (files.length > 20) {
            input.value = '';
            status.textContent = 'Choose up to 20 photos at a time.';
            return;
        }
        for (const [index, file] of files.entries()) {
            if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || !file.size || file.size > 20 * 1024 * 1024) {
                clearQueue();
                status.textContent = 'Choose JPEG, PNG or WebP files up to 20 MB each. Convert HEIC files to JPEG first.';
                return;
            }
            const card = document.createElement('article');
            card.className = 'panel photo-card';
            const preview = URL.createObjectURL(file);
            const image = document.createElement('img');
            image.src = preview;
            image.alt = 'Preview of ' + file.name;
            const fields = document.createElement('div');
            const name = document.createElement('p');
            name.className = 'filename';
            name.textContent = file.name;
            const label = document.createElement('label');
            label.htmlFor = 'description-' + index;
            label.textContent = 'Photo description (optional)';
            const alt = document.createElement('input');
            alt.id = label.htmlFor;
            alt.maxLength = 500;
            alt.placeholder = 'For example, a snowy mountain reflected in a lake';
            const orderLabel = document.createElement('label');
            orderLabel.htmlFor = 'order-' + index;
            orderLabel.textContent = 'Display order (optional, smaller numbers first)';
            const order = document.createElement('input');
            order.id = orderLabel.htmlFor;
            order.type = 'number';
            order.step = '1';
            order.min = '-2147483648';
            order.max = '2147483647';
            const result = document.createElement('p');
            result.className = 'result';
            result.setAttribute('role', 'status');
            fields.append(name, label, alt, orderLabel, order, result);
            card.append(image, fields);
            queue.append(card);
            entries.push({ file, preview, alt, order, result, published: false, duplicate: false });
        }
        byId('publish-actions').hidden = !entries.length;
        byId('publish').textContent = 'Upload & publish';
        byId('publish').disabled = false;
        status.textContent = entries.length ? 'Ready to publish. You can add descriptions if you like.' : 'Choose photos to publish.';
    });

    byId('upload-form').addEventListener('submit', async event => {
        event.preventDefault();
        if (busy || !entries.length) return;
        busy = true;
        const controls = [input, byId('publish'), byId('logout'), ...entries.flatMap(entry => [entry.alt, entry.order])];
        controls.forEach(control => control.disabled = true);
        try {
            await checkSession();
            if (!session.authenticated) throw new Error('Your session expired. Sign in again to continue.');
            for (const entry of entries.filter(item => !item.published && !item.duplicate)) {
                entry.result.className = 'result';
                entry.result.textContent = 'Uploading...';
                const body = new FormData();
                body.append('file', entry.file);
                body.append('alt', entry.alt.value);
                if (entry.order.value !== '') body.append('order', entry.order.value);
                try {
                    const response = await fetch('/api/admin/photos', { method: 'POST', headers: headers(), body });
                    if (response.status === 401 || response.status === 403) {
                        entry.result.textContent = 'Sign in again before retrying this photo.';
                        await checkSession();
                        throw new Error('Your session expired. Sign in again to continue.');
                    }
                    const result = await response.json().catch(() => ({}));
                    if (response.status === 409 && result.code === 'ALREADY_IN_GALLERY') {
                        entry.duplicate = true;
                        entry.result.className = 'result';
                        entry.result.textContent = result.message;
                        continue;
                    }
                    if (!response.ok) throw new Error(result.message || 'Upload failed. Check the file size and try again.');
                    entry.published = true;
                    entry.result.className = 'result success';
                    entry.result.textContent = 'Published to your gallery.';
                } catch (error) {
                    entry.result.className = 'result error';
                    entry.result.textContent = error.message;
                    if (!session.authenticated) throw error;
                }
            }
            const count = entries.filter(entry => entry.published).length;
            const duplicates = entries.filter(entry => entry.duplicate).length;
            status.textContent = `${count} of ${entries.length} photos published.`
                + (duplicates ? ` ${duplicates} already in gallery; skipped.` : '')
                + (count ? ' Open or refresh the gallery to see them.' : '');
        } catch (error) { status.textContent = error.message; }
        finally {
            busy = false;
            controls.forEach(control => control.disabled = false);
            entries.filter(entry => entry.published || entry.duplicate).forEach(entry => { entry.alt.disabled = true; entry.order.disabled = true; });
            const finished = entries.every(entry => entry.published || entry.duplicate);
            byId('publish').disabled = finished;
            byId('publish').textContent = finished ? 'Finished' : 'Retry unpublished photos';
        }
    });

    window.addEventListener('beforeunload', event => {
        if (busy) { event.preventDefault(); event.returnValue = ''; }
    });
    checkSession().then(() => {
        status.textContent = session.authenticated ? 'Choose photos to publish.' : 'Sign in to manage your gallery.';
    }).catch(error => { status.textContent = error.message; });
})();
