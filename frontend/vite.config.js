import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'path';
// https://vite.dev/config/
export default defineConfig({
    plugins: [
        tailwindcss(),
        react()
    ],
    resolve: {
        alias: {
            "@": path.resolve(__dirname, "./src"),
        },
    },
    build: {
        chunkSizeWarningLimit: 1000,
        rollupOptions: {
            output: {
                manualChunks: {
                    vendor: ['react', 'react-dom', '@tanstack/react-router'],
                    ui: ['lucide-react', 'recharts', 'reactflow'],
                    auth: ['oidc-client-ts', 'react-oidc-context']
                }
            }
        }
    },
    server: {
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true
            },
            '/q': {
                target: 'http://localhost:8080',
                changeOrigin: true
            },
            '/ws': {
                target: 'http://localhost:8080',
                ws: true,
                changeOrigin: true
            }
        }
    }
});
