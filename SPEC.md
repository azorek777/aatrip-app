# AA Trip: specyfikacja

## Cel
Prosta aplikacja na telefon z ekranem w Android Auto, która przez skaner OBD
rejestruje trasę (bez GPS) i liczy dystans, spalanie, zużyte paliwo i jego koszt.

## Sprzęt
| Element | Wartość |
|---|---|
| Telefon | Nothing Phone (1), Android 15 |
| Android Auto | bezprzewodowe |
| Skaner | Vgate iCar Pro, ELM327 v2.3, Bluetooth 4.0 (BLE) |
| Samochód | Skoda Octavia II (1Z5), 2013, 1.6 TDI CAYC, bez start-stop |

## Działanie
- W telefonie: przycisk „Connect” (połączenie ze skanerem) i ustawienia (cena za litr, próg wznowienia).
- W aucie: dashboard oraz przyciski Start i Zatrzymaj. Nagrywania z telefonu nie ma.
- Dashboard: dystans, spalanie aktualne, spalanie średnie, zużyte paliwo, koszt.
- Po zgaszeniu silnika (albo utracie skanera) trasa od razu widać w historii.
  O przerwie decydują dane z OBD, więc samo zerwanie Android Auto przy pracującym silniku nie przerywa trasy.
- Powrót w ciągu 15 minut (wartość do zmiany w ustawieniach) kontynuuje tę samą trasę.
- Ręczne „Zatrzymaj” zamyka trasę na stałe.
- Historia: data, czas, dystans, średnie spalanie, litry, koszt, cena za litr z dnia przejazdu.
- Bez eksportu.
- Ekran w aucie: najwyżej 3 wiersze, żeby nie trzeba było przewijać (odświeżanie cofa na górę).

## Technika
- Kotlin, Android for Cars App Library 1.7.0, kategoria IOT.
- Nagrywaniem zarządza usługa w telefonie, Android Auto jest ekranem i pilotem.
- Dane trasy zapisywane na bieżąco.
- Budowanie: GitHub Actions. Instalacja ze źródłem „Sklep Play”: KingInstaller z Shizuku (działa).

## Dane z samochodu (potwierdzone logami)
- Standardowe OBD, tryb 01, CAN 11 bit 500 kb/s. Brak PID 5E, 34, 44.
- Używane: 0C obroty, 0D prędkość, 10 przepływ powietrza (MAF), 24 lambda, 4F skala lambdy (32).
- Dystans: prędkość całkowana w czasie.
- Paliwo: l/h = MAF / (14,5 * lambda) * 3600 / 835. To szacunek.
- Lambda 0 (zimny start): powietrze jest sumowane, a paliwo doliczane proporcją z reszty trasy
  (zanim będzie dość danych, przy założonej lambdzie 3,5).
- Lambda na końcu skali: odcięcie paliwa, spalanie 0.
- Korekta spalania w ustawieniach, kalibrowana tankowaniami "do pełna".
- Silnik wyłączony: obroty 0 lub brak odpowiedzi przez 8 s.

## Etapy
1. Aplikacja testowa w Android Auto. ZALICZONE.
2. Połączenie BLE, odczyt danych, log diagnostyczny. ZALICZONE.
3. Liczenie spalania, dashboard, historia, ustawienia.  <- TERAZ (test w aucie)
4. Kalibracja i poprawki po jazdach.

## Otwarte
- Wartość korekty spalania.
- Czy Android pozwala uruchomić usługę przyciskiem w aucie przy zamkniętej aplikacji w telefonie.
- Jak dobry jest szacunek podczas zimnego startu.
