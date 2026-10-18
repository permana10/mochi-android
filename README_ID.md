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


## Build APK melalui GitHub Actions (tanpa Android Studio)

ZIP ini sudah menyertakan `.github/workflows/build-apk.yml`.

1. Buat repository baru di GitHub, misalnya `mochi-android`.
2. Upload **isi ZIP yang sudah diekstrak** ke root repository (folder `.github` harus ikut ter-upload), lalu commit ke branch `main`.
3. Buka tab **Actions** pada repository. Workflow **Build MOCHI APK** akan berjalan setelah push.
4. Tunggu sampai job hijau/berhasil.
5. Buka hasil run workflow, bagian **Artifacts**, lalu unduh `MOCHI-debug-APK`.
6. Ekstrak artifact tersebut untuk mendapatkan `app-debug.apk`.

Workflow menjalankan Gradle 8.9 + JDK 17, memasang Android SDK, lalu mengunggah APK sebagai artifact. Ini adalah APK debug untuk pengujian, bukan rilis Play Store.
