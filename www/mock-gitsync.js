// Mock of the native GitSync plugin, used until the Java plugin exists (Phase 2).
// Mirrors a folder of files: one localStorage entry mapping path -> content.
// Same shape as a Capacitor plugin: async, one object in, one object out.
const STORAGE_KEY = "gitkeep-notes";

function readFiles() {
    try {
        return JSON.parse(localStorage.getItem(STORAGE_KEY)) ?? {};
    } catch {
        return {};
    }
}

function writeFiles(files) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(files));
}

// Seeds storage with fixtures/*.md (bundled into fixtures.json by `make fixtures`).
// Only runs when storage has never been written, so deleting every note doesn't reseed.
async function seedFixtures() {
    try {
        const res = await fetch("fixtures.json");
        if (res.ok) writeFiles(await res.json());
    } catch {
        // No fixtures available: start empty.
    }
}

// Console helper: wipe the mock's notes and start again from the fixtures.
function resetFixtures() {
    localStorage.removeItem(STORAGE_KEY);
    location.reload();
}

const mockGitSync = {
    async loadAll() {
        if (localStorage.getItem(STORAGE_KEY) === null) await seedFixtures();
        const notes = Object.entries(readFiles()).map(([path, content]) => ({
            path,
            content,
        }));
        return { notes };
    },

    async saveNote({ path, content }) {
        const files = readFiles();
        files[path] = content;
        writeFiles(files);
    },

    async deleteNote({ path }) {
        const files = readFiles();
        delete files[path];
        writeFiles(files);
    },
};
