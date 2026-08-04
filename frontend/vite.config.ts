import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    // Vòng lặp nhanh: Vite :5173 proxy /api/* sang bootRun :8080.
    // LƯU Ý: proxy này KHÔNG phải CloudFront behavior — nó không tái hiện
    // OAC, không tái hiện cache policy. Xem TDD §11 dòng "Frontend".
    proxy: { '/api': 'http://localhost:8080' },
  },
})
