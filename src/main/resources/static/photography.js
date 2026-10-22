(() => {
    const grid = document.getElementById('gallery');
    const status = document.getElementById('gallery-status');
    const retry = document.getElementById('gallery-retry');
    let loading = false;

    async function loadGallery() {
        if (loading) return;
        loading = true;
        retry.hidden = true;
        grid.setAttribute('aria-busy', 'true');
        if (!grid.children.length) status.textContent = 'Loading photographs…';
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 30000);
        try {
            const response = await fetch('/api/gallery', { signal: controller.signal, cache: 'no-store' });
            if (!response.ok) throw new Error('Gallery request failed');
            const photos = await response.json();
            if (!Array.isArray(photos)) throw new Error('Invalid gallery response');
            const fragment = document.createDocumentFragment();
            for (const photo of photos) {
                const item = document.createElement('div');
                item.className = 'masonry-item shadow-2xl shadow-black/50';
                const img = document.createElement('img');
                img.src = photo.url;
                img.alt = photo.alt;
                img.loading = 'lazy';
                img.decoding = 'async';
                if (photo.width > 0 && photo.height > 0) {
                    img.width = photo.width;
                    img.height = photo.height;
                }
                item.append(img);
                fragment.append(item);
            }
            grid.replaceChildren(fragment);
            status.textContent = photos.length ? '' : 'New photographs are coming soon.';
        } catch (error) {
            status.textContent = grid.children.length
                ? 'Unable to refresh photographs. Please try again shortly.'
                : 'The gallery is temporarily unavailable. Please try again shortly.';
            retry.hidden = false;
        } finally {
            clearTimeout(timeout);
            grid.setAttribute('aria-busy', 'false');
            loading = false;
        }
    }
    retry.addEventListener('click', loadGallery);
    loadGallery();
    setInterval(() => { if (!document.hidden) loadGallery(); }, 300000);
})();
