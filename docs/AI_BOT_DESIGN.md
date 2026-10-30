# Thiết kế bot AI cho FPAutoMine

Mục tiêu: bot tự chơi Minecraft (Paper 1.21.11) qua FakePlayerPlus, từ sinh tồn đến phá đảo, kết bạn và lập liên minh, xây dựng công trình, và làm được gần như mọi việc trong game.

## Ranh giới

Bot không được:

- phá, lấy hoặc chiếm công trình, rương, đồ của người chơi khác;
- tấn công người chơi không thù địch;
- tự nhận là người thật trong chat (bot luôn nói rõ mình là bot);
- kết bạn với người chơi khi họ chưa đồng ý;
- hoạt động vượt quá vùng và số lượng bot mà chủ server cho phép.

Bot phải tôn trọng claim và vùng bảo vệ của các plugin (WorldGuard, GriefPrevention và tương tự). Chủ bot có lệnh dừng khẩn cấp và được báo cáo hành động.

## Kiến trúc

Bot ra quyết định theo kiểu **utility AI**: mỗi tick, mỗi việc (task) tính một điểm từ 0 đến 1 dựa trên trạng thái hiện tại, rồi bot chọn việc điểm cao nhất.

- **Snapshot**: ảnh chụp trạng thái bot (đói, máu, ban đêm, quái gần, có đồ ăn, có giường, có công cụ, đang nguy hiểm). Lớp này không gọi API Bukkit nên kiểm thử được bằng dữ liệu giả.
- **Task**: một việc. Có `utility(Snapshot)` để chấm điểm, và (từ M2) `tick()` để thực hiện.
- **Brain**: chọn task điểm cao nhất. Có độ trễ đổi việc (hysteresis) để bot không đổi việc liên tục khi điểm gần nhau.
- **Skills**: các kỹ năng cấp thấp dùng lại được: đào (đã có `AutoMineSession`), đi (`Pathfinder`), ăn (`eatFood`), an toàn (`Safety`), tấn công và dùng đồ (action của FakePlayerPlus).
- **Memory**: vị trí rương, giường, căn cứ, khu vực quặng đã thấy, danh sách bạn bè và đồng minh.
- **Social**: bảng quan hệ cho từng người. Trạng thái gồm: người lạ, bạn, đồng minh, kẻ thù. Kết bạn với người chơi thật cần họ xác nhận bằng lệnh.

## Lộ trình

| Giai đoạn | Nội dung | Tiêu chí hoàn thành |
|---|---|---|
| **M1. Lõi quyết định** | `Brain`, `Snapshot`, `Task`, các task chấm điểm cơ bản | Chọn đúng việc với các trạng thái giả (đói, nguy hiểm, bình thường) |
| **M2. Sinh tồn** | Task ăn, chạy trốn, chiến đấu, ngủ | Bot tự ăn, tự đánh quái khi bị tấn công, tự ngủ khi có giường và đêm xuống |
| **M3. Tài nguyên và chế tạo** | Thu thập qua `AutoMine`, bộ lập kế hoạch công thức, chế tạo qua container | Từ gỗ đến công cụ sắt, rồi kim cương |
| **M4. Xây dựng** | Đọc schematic, hàng đợi đặt block, dùng chế độ build của `Pathfinder` | Đặt xong một công trình nhỏ đúng bản thiết kế |
| **M5. Xã hội** | Bảng quan hệ, chat, đồng minh bot với bot, kết bạn với người chơi có đồng ý, chia sẻ đồ có xác nhận | Hai bot cùng nhóm chia việc; người chơi đồng ý kết bạn thì bot nhận |
| **M6. Phá đảo** | Nether, blaze rod, ender pearl, mắt ender, Stronghold, Ender Dragon | Hạ được Ender Dragon |

## Rủi ro và điều chưa kiểm chứng

- FakePlayerPlus có action cho tấn công, dùng đồ, đào và chat. Việc ngủ giường và chế tạo qua container chỉ có hàm NMS (`useItemOnBlock`, `openContainer`, `quickMoveStack`), **chưa kiểm chứng**.
- Không có server để chạy thử trong phiên này. Mọi giai đoạn từ M2 trở đi cần chạy trên server thật.
- M6 là phần khó nhất: chuỗi công việc dài, nhiều nguy hiểm (dung nham, void, lính Piglin, Ender Dragon). Dự kiến nhiều lần điều chỉnh.
