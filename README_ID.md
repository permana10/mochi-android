# MOCHI Android — BLE Notifikasi + Sinkronisasi Jam

Versi awal minimal untuk proyek Android Kotlin, tanpa library UI tambahan.

## Fitur
- Memindai perangkat BLE bernama `MOCHI`.
- Menghubungkan ke service UUID yang sudah diuji pada ESP32-C3.
- Mengirim waktu Unix (detik) melalui `TIME|<epoch>`.
- Meneruskan judul dan isi notifikasi Android melalui `NOTIF|<app>|<sender>|<message>` langsung dari NotificationListenerService, termasuk saat layar aplikasi Mochi tidak sedang terbuka.
- Menampilkan status/log sederhana.

## Penting tentang firmware ESP32-C3
Firmware saat ini memakai protokol teks dipisahkan `|`:
`NOTIF|app|sender|message`
Karena itu karakter `|`, CR, dan LF dalam nilai notifikasi diganti spasi.

## Cara build
1. Salin isi folder ZIP ke folder proyek sementara, jangan menimpa `/opt/skeybox` dulu.
2. Pastikan JDK 17 tersedia dan Gradle bisa mengunduh plugin/dependency.
3. Jalankan `./gradlew assembleDebug`.
4. APK debug berada di `app/build/outputs/apk/debug/app-debug.apk`.

## Cara tes pada Pixel 4a
1. Instal APK.
2. Buka Mochi dan izinkan Bluetooth / Nearby devices.
3. Tekan "Cari / Hubungkan MOCHI". ESP32-C3 harus menyala dan tidak sedang terhubung ke aplikasi lain.
4. Tekan "Aktifkan Akses Notifikasi", lalu aktifkan akses untuk Mochi di pengaturan Android.
5. Setelah BLE tersambung, aplikasi otomatis mengirim waktu. Tombol "Sinkronkan Jam" dapat mengirim ulang.
6. Kirim notifikasi uji dari aplikasi lain; Mochi meneruskannya jika koneksi BLE tersedia.

## Batasan versi awal
- Ini aplikasi minimal untuk uji fungsi, belum service BLE foreground permanen. Android dapat membatasi koneksi saat aplikasi ditutup atau dibatasi baterainya. Untuk penggunaan stabil 24/7, tahap berikutnya dapat menambahkan foreground service.
- APK debug belum ditandatangani dengan keystore produksi.
- Proyek ini dibuat sebagai struktur dasar independen; integrasi dengan aplikasi Soundbox lama harus dilakukan setelah file proyek lama diperiksa.


## Revisi 1.1 — pemantauan koneksi BLE aktif

Pada versi ini, status “komunikasi terverifikasi” tidak hanya berdasarkan PONG pertama. Aplikasi mengirim PING berkala dan memeriksa PONG terbaru. Jika tidak ada PONG selama sekitar 7 detik, aplikasi menandai koneksi hilang lalu mencoba menyambungkan kembali. Sinkronisasi jam otomatis hanya dijalankan pada handshake awal, bukan setiap heartbeat.

Firmware ESP32-C3 harus membalas perintah `PING` dengan `PONG`. Firmware MOCHI yang memakai protokol tersebut sudah memiliki handler PING/PONG.

Catatan: sumber ini belum dikompilasi atau diuji pada perangkat fisik dalam lingkungan ini. Buka folder proyek dengan Android Studio, lakukan Sync/Build, lalu uji dengan mematikan daya ESP32 saat koneksi aktif. Status tidak boleh terus menampilkan komunikasi terverifikasi; setelah batas timeout, status harus berubah menjadi koneksi hilang/menyambungkan ulang.
