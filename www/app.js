const { markedHighlight } = globalThis.markedHighlight;
marked.use(
    markedHighlight({
        emptyLangClass: "hljs",
        langPrefix: "hljs language-",
        highlight(code, lang) {
            const language = hljs.getLanguage(lang) ? lang : "plaintext";
            return hljs.highlight(code, { language }).value;
        },
    }),
);

// Real plugin when it is registered on the native side, mock otherwise.
const GitSync = window.Capacitor?.Plugins?.GitSync ?? mockGitSync;

document.addEventListener("alpine:init", () => {
    Alpine.data("gitkeep", () => ({
        notes: [],
        current: null,
        preview: false,

        async init() {
            await this.reload();
        },

        async reload() {
            const { notes } = await GitSync.loadAll();
            this.notes = notes;
        },

        get html() {
            return marked.parse(this.current?.content ?? "");
        },

        open(note) {
            this.current = { ...note };
            this.preview = false;
        },

        create() {
            const name = prompt("Note name");
            if (name === null) return; // aborted
            if (name.trim() === "") {
                alert("A note's name cannot be empty");
                return;
            }
            this.open({ name, content: "" });
        },

        async save() {
            await GitSync.saveNote(this.current);
            await this.reload();
        },

        close() {
            this.save();
            this.current = null;
        },

        async remove() {
            await GitSync.deleteNote({ path: this.current.path });
            this.current = null;
            await this.reload();
        },
    }));
});
