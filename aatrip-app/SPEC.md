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
- Po zgaszeniu silnika lub rozłączeniu Android Auto trasa od razu zapisuje się w historii.
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

## Dane z samochodu (do potwierdzenia na etapie 2)
- Dystans: prędkość całkowana w czasie.
- Paliwo, w kolejności preferencji:
  1. zużycie w l/h lub dawka wtrysku z bloków pomiarowych VW (TP 2.0),
  2. standardowy PID zużycia paliwa (015E), jeśli auto go obsługuje,
  3. szacunek z przepływu powietrza i lambdy (plan awaryjny).
- Średnie liczy aplikacja. Współczynnik korekcyjny ustawiany po tankowaniach „do pełna”.

## Etapy
1. Aplikacja testowa w Android Auto. ZALICZONE 2026-11-25.
2. Połączenie BLE, odczyt danych, log diagnostyczny.  <- TERAZ
3. Liczenie spalania i porównanie z deską rozdzielczą.
4. Dashboard, historia, ustawienia.

## Otwarte
- Które parametry zgłasza sterownik (z logu etapu 2).
- Czy potrzebne są bloki pomiarowe VW (TP 2.0).
