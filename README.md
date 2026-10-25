# SafeCard – bản đầy đủ (Giai đoạn 1 + 2 + 3)

Android (Java, XML/Views, minSdk 26, package `com.example.safecard`) + Firebase (Auth OTP, Realtime Database, Hosting). **Không cần gói Blaze, không có Cloud Functions.**
Vì không có server, mọi logic chạy trên máy người dùng và được "khóa" bằng Security Rules (`database.rules.json`). Xem mục 8 để biết hệ quả.

## 1. Mở dự án
Mở thư mục `SafeCard` bằng Android Studio, chờ Gradle sync (zip không có `gradlew`, Android Studio tự tải Gradle 8.7). Đổi package nếu muốn: `app/build.gradle` (namespace, applicationId), dòng `package` ở đầu mọi file `.java`, và `com.example.safecard` trong `res/xml/shortcuts.xml`.

## 2. Nối Firebase (làm một lần)
1. Tạo project Firebase, thêm app Android (package `com.example.safecard`).
2. `./gradlew signingReport` → dán **SHA-1 và SHA-256** vào Firebase. Thiếu thì OTP lỗi.
3. **Authentication → Sign-in method:** bật **Phone** (thêm vài số test + mã) và bật **Anonymous** (cần cho nút "Báo người thân" trên trang thẻ QR).
4. **Realtime Database → Create database** (làm trước khi tải file ở bước 5).
5. Tải `google-services.json` đặt vào `app/`.
6. **Realtime Database → Rules:** dán toàn bộ `database.rules.json` → Publish. (Mọi tính năng mới đều phụ thuộc file này.)
7. Mở `Config.java`: **đổi `ID_PEPPER`** thành chuỗi riêng của bạn.

## 3. Tạo tài khoản admin (bắt buộc để duyệt CCCD)
1. Đăng nhập app bằng số của bạn, rồi vào Firebase Console → Realtime Database, xem node `users` để lấy **UID** của mình.
2. Tạo tay node: `admins` → `<UID của bạn>` = `true` (kiểu boolean). Thoát và mở lại app: màn hình chính có nút **Admin**.
3. Phân quyền admin ở đây thay cho custom claims (cần server). Bật xác thực 2 bước cho tài khoản Google đang quản trị Firebase.

## 4. Hosting (tùy chọn nhưng cần cho thẻ QR dạng liên kết và nút "Báo người thân")
1. Điền cấu hình Firebase vào `web/index.html` và `web/card.html` (apiKey, databaseURL, projectId có trong `google-services.json`).
2. Cài firebase-tools → `firebase init hosting` (public = `web`) → `firebase deploy --only hosting`.
3. Điền `Config.TRACK_BASE_URL = "https://<project>.web.app"`. Không làm bước này thì SMS vẫn có link Google Maps và QR chứa văn bản trực tiếp.

## 5. Các tính năng và cách thử
| Tính năng | Cách thử |
|---|---|
| Đăng nhập OTP, hồ sơ y tế (người thân / tình nguyện viên / QR theo từng trường) | Hồ sơ y tế |
| Người thân xác minh bằng OTP (hết hạn 7 ngày, rút lại được) | Người thân (cần 2 máy) |
| SOS: bấm nguồn 5 lần + đếm ngược 10 s + mã hủy/cưỡng ép | Thiết lập bảo vệ → bật |
| Giữ 2 s: shortcut, Quick Settings Tile, thông báo, widget màn hình chính, nút trong app | nhấn giữ icon app / thêm widget "SOS" / sửa Quick Settings |
| Giữ 2 nút âm lượng 3 s | Thiết lập → nút 6 (Trợ năng) |
| Lắc 3 lần, té ngã (mặc định tắt) | Thiết lập → công tắc |
| Người thân nhận cảnh báo, "Tôi đang đến", trạng thái 3 mức, SMS sau 30 s, nhắn tin trong app | 2 máy |
| **Cấp 1:** xác minh CCCD (OCR + selfie) → admin duyệt | Xác minh danh tính → màn hình Admin |
| **Tình nguyện viên:** bật vai trò, GeoFire, gửi theo đợt 1 km → 3 km, "Nhận hỗ trợ" mới thấy vị trí chính xác, tự ghi "đã có mặt" khi cách < 100 m, nhật ký ai xem gì | cần ≥ 2 tài khoản cấp 1 (admin duyệt cả hai) |
| Điểm uy tín: 100, −20 báo nhầm, +20 giúp được (trần 100), điểm < 60 phải bấm xác nhận, về 0 khóa 30 ngày, khiếu nại | sau khi bấm "Đã an toàn" |
| Mã QR + thẻ y tế công khai (2 nhãn: danh tính đã xác minh / thông tin tự khai), thu hồi, tạo lại, chia sẻ | Mã QR của tôi |
| Hồ sơ phụ (con nhỏ), giấy khai sinh, admin duyệt | Hồ sơ phụ |
| Admin: duyệt CCCD/giấy khai sinh, khiếu nại, khóa tài khoản, **thống kê báo nhầm theo từng trigger** (dùng cho báo cáo) | Admin |
| Đồng ý chính sách + xóa dữ liệu | Chính sách & xóa dữ liệu |

Khi demo: tắt SOS hệ thống của máy (Pixel/Samsung cũng dùng bấm nguồn 5 lần). Dùng số bạn bè/máy thứ hai làm người thân, **đừng** thêm 113/115 (SMS thật gây báo động giả).

## 6. Build APK
Android Studio: Build → Build APK(s) → `app/build/outputs/apk/debug/app-debug.apk`. Bản release: Build → Generate Signed App Bundle/APK (giữ kỹ keystore, thêm SHA-1/SHA-256 của khóa release vào Firebase; đã tắt R8 để khỏi hỏng lớp dữ liệu Firebase). Cài APK trên Android 13+: nếu quyền (trợ năng, SMS…) bị mờ → Thông tin ứng dụng > ⋮ > *Cho phép cài đặt bị hạn chế*.

## 7. Kiểm thử đáng làm cho báo cáo
Tỷ lệ báo nhầm theo trigger (bỏ máy vào túi khi chạy bộ/đi xe/đi cầu thang; xem số liệu ở màn Admin), độ trễ từ kích hoạt đến lúc người thân nhận, hao pin dịch vụ nền 1 giờ, mất mạng (SMS có đi không), bảo mật (thử đọc `users/<uid người khác>` phải bị từ chối).

## 8. Những chỗ KHÁC đặc tả vì không có server (ghi vào báo cáo)
- **Điểm uy tín** không do server ghi: được tính từ sổ cái `pointLedger`, chỉ ghi được theo điều kiện chặt trong Rules (−20 chỉ tự ghi cho mình khi đã báo nhầm; +20 chỉ do người gặp nạn ghi khi tình nguyện viên đã ở trạng thái "đã có mặt" và cặp này chưa được cộng trong 30 ngày). Giới hạn cộng mỗi ngày chưa được ép. Trạng thái "đã có mặt" và điểm hiển thị do máy tình nguyện viên tự ghi nên vẫn có thể bị gian lận nếu ai đó sửa app.
- **Gửi theo đợt cho tình nguyện viên** chạy trên máy người gặp nạn: nếu app bị hệ điều hành tắt giữa chừng thì đợt sau không tự chạy.
- **Chống trùng CCCD**: dùng SHA-256 + chuỗi cố định `ID_PEPPER` nằm trong app (không phải HMAC bí mật phía server), nên ai có APK có thể thử dò một số CCCD cụ thể. Ảnh CCCD/selfie lưu tạm dạng base64 trong Realtime Database (vì Firebase Storage nay cần gói Blaze) và bị xóa khi admin duyệt/từ chối.
- **Play Integrity** chưa làm được (cần server kiểm token). Chỉ có kiểm tra `Location.isMock` (GPS giả).
- **Nút "Báo người thân" trên trang QR** gửi tín hiệu tới máy chủ thẻ; máy đó phải đang online và bật bảo vệ nền. Giới hạn 1 lần/10 phút/thẻ bằng Rules.
- Người thân nhận cảnh báo bằng dịch vụ nền nghe Realtime Database (không có FCM): tốn pin và có thể bị hãng máy tắt.
- Giấy khai sinh không có ảnh và không chứng minh nhóm máu/dị ứng; admin chỉ đối chiếu tên cha/mẹ với CCCD người giám hộ. Dữ liệu trẻ em tham chiếu Nghị định 13/2023/NĐ-CP (cần đọc lại để trích dẫn chính xác).
- Không đối chiếu được VNeID: chặn được trùng lặp/giả mạo rõ ràng, không chặn được người dùng CCCD của người khác. Cấp 0 có thể tạo nhiều tài khoản bằng nhiều số điện thoại.

## 9. Đã biết / chưa kiểm chứng
Dự án chưa được build trong môi trường soạn thảo (không có Android SDK). Nếu Gradle báo lỗi biên dịch, copy nguyên văn lỗi để sửa. Chưa làm: bản đồ Google Maps SDK (dùng ứng dụng bản đồ qua intent `geo:`), ảnh đại diện tình nguyện viên, đối chiếu khuôn mặt TFLite, giới hạn cộng điểm mỗi ngày.
