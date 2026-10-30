# FPAutoMine — addon đào tự động cho FakePlayerPlus

Addon thêm action `automine` cho [FakePlayerPlus](https://github.com/xiplugin/FakePlayerPlus) (Paper 1.21.x). Bot tự đi tìm block mục tiêu và đào, có thể cất đồ vào rương gần đó.

## Cú pháp

```
/fp action start mine <tên block> [số lượng] [tên bot]
```

Có thể dùng dạng ngắn `/fpautomine start|stop ...`, ví dụ `/fpautomine start minecraft:stone 128 Miner01`.

- **tên block**: tên Material Paper, ví dụ `STONE`, `DIAMOND_ORE`, `OAK_LOG`.
- **số lượng** (tùy chọn): số block cần đào. Bỏ trống, `-` hoặc `*` nghĩa là không giới hạn (đào đến khi túi đầy).
- **tên bot** (tùy chọn): bắt buộc nếu bạn sở hữu nhiều hơn một bot. Nếu chỉ có một bot thì addon tự chọn bot đó.

Ví dụ:

```
/fp action start mine DIAMOND_ORE 64 Bot1      # đào 64 kim cương quặng
/fp action start mine STONE Bot1               # đào đến khi túi đầy
/fp action start mine OAK_LOG 128              # đào 128 gỗ, bot tự chọn
/fpautomine start minecraft:stone 128 Miner01  # đào 128 đá bằng dạng ngắn
```

Dừng: `/fp action stop mine [tên bot]`. Xem trạng thái: `/fpautomine status [tên bot]`.

Gõ phím Tab sau `mine` để gợi ý: tên block (ví dụ `stone`), số lượng gợi ý (`-`, `16`, `64`, `128`...), và tên bot của bạn (mọi bot nếu bạn là admin).

Quyền cần có: `fakeplayer.action` và `fakeplayer.action.mine`. Người điều khiển phải là chủ bot hoặc có `fakeplayer.admin`.

## Hành vi

1. Bot ưu tiên đào block ngay trước mặt (kể cả block dưới chân phía trước) nếu đào được. Nếu không có, bot đào các block khác trong tầm với mà không cần đi. Sau đó mới tìm block cùng loại xa hơn theo thứ tự: lớp gần hơn trước; trong cùng một lớp đi theo trái→phải, rồi trước→sau, rồi trên→dưới. Bot có thể đào xuyên block chắn để tới nguyên liệu. Nếu không thấy, bot đi khám phá ngẫu nhiên 16–32 block, và có thể đào xuyên đá, đất để tới vùng mới.
2. Đào xong, bot nhặt đồ rơi trong bán kính 1.5 block và cập nhật số đã đào.
3. **Rương được chỉ định**: rương gần bot nhất trong 10 block lúc bắt đầu lệnh (nếu có). Nếu lúc đó không có, rương đầu tiên bot dùng để cất đồ sẽ trở thành rương được chỉ định. Bot nhớ rương này trong suốt phiên.
4. Khi xong nhiệm vụ, tức là đạt chỉ tiêu, hoặc khám phá hết lượt (60 lần) mà không thấy block nào:
   - bot quay về rương đã chỉ định, dù đang ở xa (nếu không tới được thì dùng rương khác đã nhớ hoặc rương gần nhất trong 10 block);
   - **cất toàn bộ đồ trong túi**, kể cả công cụ và đồ ăn;
   - cất xong hết thì bot **tự biến mất** khỏi server.
   Nếu không rương nào tới được hoặc rương đầy, bot giữ đồ và đứng im (không biến mất).
5. Khi túi đầy giữa chừng (chế độ không giới hạn):
   - **Có rương trong 10 block**: bot đi tới rương (nếu rương không với tới bằng đường đi bộ, bot tự phá block chắn và đặt block lấp đất đỡ lấy từ túi), cất đồ rồi tiếp tục đào. Công cụ và đồ ăn được giữ lại trong túi để bot đào tiếp;
   - **Không có rương**: bot giữ đồ trong túi và đứng im.
6. Bot được coi như NPC: quái không nhắm vào bot.
7. Bot nhớ mọi rương đã thấy trong phiên (tối đa 16) và giữ chunk của chúng được nạp, nên vẫn quay về được rương khi đi xa.
8. Khi đang chạy lệnh auto-mine, cứ 3 giây addon xoá quái (nhện, zombie, skeleton, creeper và các mob thù địch khác) và dơi, phantom trong bán kính 25 block quanh bot. Mob đang ngồi thuyền/xe, hoặc đang chở mob khác, không bị xoá. Ngoài lúc chạy lệnh, addon không xoá gì.

Lệnh `/fp action start mine` không kèm tham số vẫn mở GUI gốc của FakePlayerPlus.

## Cài đặt

Addon cần FakePlayerPlus đã cài trên server. Server phải chạy Java 25, vì FakePlayerPlus được build cho Java 25.

### Build

1. Cài API của FakePlayerPlus vào Maven local (trong thư mục repo FakePlayerPlus):

   ```
   ./gradlew :api:publishToMavenLocal
   ```

   Lệnh này tạo artifact `com.coderxi.plugin.fakeplayer:api:dev`, mà `build.gradle.kts` của addon đang dùng.

2. Build addon (cần JDK 25; dùng Gradle 9.x hoặc `gradle` thay cho `./gradlew`):

   ```
   ./gradlew build
   ```

3. Lấy file `build/libs/FPAutoMine-1.0.0.jar`, đặt vào thư mục `plugins/` của server và khởi động lại.

## An toàn

Bot kiểm tra an toàn cho từng nước đi và từng lần đào (lớp `Safety`):

- **Rơi vào void:** bot không đứng sát đáy thế giới (cách đáy dưới 4 block). Không đào block ngay dưới chân nếu bot sẽ rơi quá 3 block, hoặc rơi vào chất lỏng hay void.
- **Ngạt do block:** bot không đi vào ô có block đặc ở đầu hoặc chân. Nếu lỡ bị kẹt trong block, bot phá block đó trước.
- **Dung nham, lửa, nước, magma:** bot không đi vào và không đứng trên các loại này. Lộ trình tránh sát dung nham và lửa (phạt rất nặng), và tránh nước (phạt nhẹ). Bot không đào block kề chất lỏng, vì chất lỏng sẽ chảy vào chỗ trống. Nếu lỡ ở trong chất lỏng hoặc lửa, bot thoát ngay theo đường thoát hiểm, được phép đi qua chất lỏng để ra ngoài.
- **Block rơi (cát, sỏi):** không đào block có cát/sỏi nằm trên đầu khi bot đứng cùng cột, vì block rơi sẽ đè lên bot.
- **Đói:** dưới 14 điểm đói, bot tự ăn một món trong túi. Bot không ăn thịt gà sống, thịt thối, mắt nhện, khoai tây độc, cá nóc, và chorus fruit (chorus làm bot dịch chuyển ngẫu nhiên, có thể vào dung nham hoặc void). Đói nguy kịch mà không còn đồ ăn thì bot dừng và báo chủ.
- **Kiểm tra từng bước:** trước mỗi bước đi, bot kiểm tra lại ô kế tiếp. Trong lúc đào, bot kiểm tra lại block đang đào. Nếu thế giới đã đổi (chất lỏng chảy, block rơi), bot tính lại đường thay vì bước vào.

Giới hạn: lộ trình chỉ dựa vào trạng thái block hiện có. Addon không mô phỏng hướng chảy của chất lỏng theo thời gian, không xử lý khi bot đang cháy (fire tick), và không hồi máu.

## Cấu trúc mã nguồn

| File | Vai trò |
|---|---|
| `AutoMineAction` | Định nghĩa action `automine` |
| `AutoMineHandler` | Nhận tick từ FakePlayerPlus, giữ phiên của từng bot theo UUID |
| `AutoMineSession` | Máy trạng thái: lập kế hoạch → đi → đào → xác nhận → cất đồ |
| `Pathfinder` | Tìm đường A* trên lưới block, có chế độ khám phá và thoát hiểm |
| `Safety` | Quy tắc an toàn: void, ngạt, chất lỏng/lửa, block rơi, rơi cao |
| `Throttle` | Ngân sách công việc nặng mỗi tick server, dùng chung cho mọi bot, tự giảm khi server chậm |
| `BlockView` | Nguồn đọc block: đọc thế giới (thread chính) hoặc ảnh chụp chunk (thread phụ) |
| `AsyncPlanner` | Chụp ảnh chunk, giao việc tìm đường cho thread phụ và trả kết quả về |
| `Settings` | Đọc `config.yml`, kẹp giá trị trong khoảng hợp lệ |
| `CommandListener` | Chặn lệnh `/fp action start mine ...` và khởi chạy action |
| `FPAutoMinePlugin` | Điểm vào của plugin, đăng ký action và listener |

## Hiệu năng

Việc nặng nhất (tìm đường A*, quét block quanh bot) không chạy trong một tick nữa mà được chia nhỏ qua nhiều tick:

- Tổng công việc tìm đường và quét của **mọi bot** bị giới hạn mỗi tick server (lớp `Throttle`). Khi thời gian mỗi tick tăng (server đang chậm), giới hạn này tự giảm.
- Mỗi bot chỉ mở rộng tối đa 400 nút A* và quét tối đa 3000 ô mỗi tick; toàn bộ server dùng chung khoảng 375 nút A* hoặc 6000 ô mỗi tick.
- Hệ quả: bot có thể đứng chờ vài tick (vài phần mười giây) trước khi bắt đầu đi tới block mới, nhất là khi có nhiều bot cùng chạy.
- Các mob-sweep của các bot được lệch ngẫu nhiên, không quét cùng tick.
- Tìm đường đi bộ tới block, tới đồ rơi và khám phá chạy trên thread phụ (`search.async`), dùng ảnh chụp chunk. Kết quả luôn được kiểm tra lại trên thread chính trước khi bot đi.
- Quét block mở rộng dần theo bán kính (8, 16, 24 mặc định): block gần được tìm thấy trước, không phải quét hết hộp lớn.
- Việc cất đồ (tìm đường tới rương) và thoát hiểm vẫn chạy đồng bộ trong một tick, vì cần kết quả ngay.

## Cấu hình

File `plugins/FPAutoMine/config.yml` được tạo khi bật plugin lần đầu. Sửa xong thì khởi động lại server để áp dụng.

| Khoá | Mặc định | Ý nghĩa |
|---|---|---|
| `throttle.full-units` | 6000 | Ngân sách công việc nặng mỗi tick cho cả server; tự giảm khi server chậm |
| `throttle.expand-cost` | 16 | Số đơn vị một nút A* tốn |
| `search.step-nodes` | 400 | Số nút A* tối đa một bot mở rộng mỗi tick (đường đồng bộ) |
| `search.async` | true | Tìm đường đi bộ trên thread phụ; đặt `false` nếu gặp lỗi lạ |
| `scan.units-per-step` | 3000 | Số ô tối đa một bot quét mỗi tick |
| `scan.radii` | [8, 16, 24] | Bán kính quét ngang, tăng dần |
| `mining.vein` | true | Đào theo mạch: block cùng loại kề bên được đào luôn, không tìm đường |
| `mob-sweep.interval-ticks` | 60 | Chu kỳ quét quái, tính bằng tick |
| `mob-sweep.radius` | 25.0 | Bán kính quét quái, tính bằng block |
| `ai.enabled` | false | Bật bot AI. Tắt thì plugin không gọi mạng |
| `ai.model` | claude-haiku-5-5 | Mô hình Claude dùng để quyết định |
| `ai.api-key-env` | ANTHROPIC_API_KEY | Tên biến môi trường chứa khoá API. Khoá không ghi trong config |
| `ai.decision-interval-ticks` | 600 | Chu kỳ hỏi AI cho mỗi bot (600 tick = 30 giây) |
| `ai.max-calls-per-hour` | 120 | Số lần gọi Claude tối đa mỗi giờ, cho mọi bot |
| `ai.bots` | [] | Danh sách bot tự spawn khi bật plugin: `name`, và tuỳ chọn `world`, `x`, `y`, `z` |

## Bot AI

Bật bằng `ai.enabled: true`, khai báo bot trong `ai.bots`, rồi đặt khoá vào biến môi trường (ví dụ `export ANTHROPIC_API_KEY=...`) trước khi khởi động server. Thiếu biến môi trường thì AI không chạy và log ghi rõ lý do.

- Bot được spawn bằng lệnh console `fp spawn <tên>`, nên không thuộc người chơi nào. Chỉ admin (`fakeplayer.admin`) và console điều khiển được. Dừng khai thác: `/fpautomine stop <tên>`. Xoá bot: `/fp remove <tên>`.
- Mỗi chu kỳ, plugin gửi cho Claude các số liệu của bot (đói, máu, ban đêm, số quái gần, có đồ ăn, có cuốc). Không gửi tên người chơi hay chat.
- Claude chỉ được chọn `gather` với block trong danh sách cho phép (gỗ `*_LOG`, quặng `*_ORE`, và STONE, DEEPSLATE, COBBLESTONE, DIRT, GRAVEL, SAND, CLAY, NETHERRACK) hoặc `rest`. Câu trả lời ngoài danh sách bị bỏ qua.
- Bot đang ở trong nước hoặc lửa thì không hỏi AI.
- Chưa có: chạy trốn, ăn, đánh quái, ngủ (giai đoạn M2); tôn trọng plugin bảo vệ (WorldGuard, GriefPrevention); bot tự báo là bot trong chat; kết bạn và liên minh.

## Trạng thái bot

`/fpautomine status [tên bot]` cho biết bot đang ở pha nào (lập kế hoạch, đi, đào, kiểm tra), đã đào bao nhiêu, có đang quét hoặc đang tính đường không, số rương đã nhớ và số lượt khám phá.

## Hạn chế và trạng thái kiểm thử

- **Chưa chạy trên server thật.** Mã nguồn đã được biên dịch từng file với source Paper API và stub của API FakePlayerPlus, không có lỗi trong addon. Hành vi khi chạy (di chuyển, đào, cất đồ) chưa được kiểm chứng.
- Không hỗ trợ Folia. Addon tự tắt nếu phát hiện Folia.
- Trong GUI, nhãn action hiển thị là `fakeplayer.action.automine` vì chưa có bản dịch.
- `/fp action execute automine` không kiểm tra quyền `fakeplayer.action.mine` vì type này không khai báo quyền.
- Bot chỉ nhặt đồ rơi trong bán kính 1.5 block.
- Đường về rương chỉ đi qua các chunk đang được nạp. Các rương đã nhớ được giữ chunk nạp, nhưng đường đi giữa bot và rương vẫn có thể bị chặn nếu đi qua vùng xa chưa nạp.
- Dừng bằng `/fp action stop mine` không cất đồ; đồ vẫn nằm trong túi bot.
- Nếu bot bị kẹt khi đi, addon dịch chuyển bot tới điểm tiếp theo trên đường đi.
- Phá và đặt block khi đi tới rương là tức thời (không có hoạt ảnh). Addon không kiểm tra vùng bảo vệ (claim, spawn protection), nên hãy cẩn thận khi bot làm việc gần khu vực được bảo vệ.
- Addon không phá rương, lò, phễu và các vật chứa đồ khác khi mở đường.
- Khi chạy auto-mine, addon xoá quái trong bán kính 25 block mỗi 3 giây, nên quái gần như không gây sát thương được. Ngoài lúc chạy lệnh, quái vẫn bình thường; addon chỉ chặn việc quái nhắm mục tiêu vào bot.
