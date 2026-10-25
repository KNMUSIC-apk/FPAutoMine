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

Dừng: `/fp action stop mine [tên bot]`.

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

## Cấu trúc mã nguồn

| File | Vai trò |
|---|---|
| `AutoMineAction` | Định nghĩa action `automine` |
| `AutoMineHandler` | Nhận tick từ FakePlayerPlus, giữ phiên của từng bot theo UUID |
| `AutoMineSession` | Máy trạng thái: lập kế hoạch → đi → đào → xác nhận → cất đồ |
| `Pathfinder` | Tìm đường A* trên lưới block, có chế độ khám phá |
| `CommandListener` | Chặn lệnh `/fp action start mine ...` và khởi chạy action |
| `FPAutoMinePlugin` | Điểm vào của plugin, đăng ký action và listener |

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
