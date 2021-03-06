package prs.project.checker;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import prs.project.model.Product;
import prs.project.model.Warehouse;
import prs.project.status.ReplyToAction;
import prs.project.task.Akcja;
import prs.project.task.SterowanieAkcja;
import prs.project.task.WycenaAkcje;
import prs.project.task.WydarzeniaAkcje;
import prs.project.task.ZamowieniaAkcje;

import static org.assertj.core.api.Assertions.assertThat;

@Service
@Getter
@Setter
@Slf4j
@NoArgsConstructor
public class Ledger {

    ConcurrentHashMap<Long, ArrayList<Akcja>> actions = new ConcurrentHashMap<>();
    ConcurrentHashMap<Long, ArrayList<ReplyToAction>> logActions = new ConcurrentHashMap<>();
    ArrayList<ReplyToAction> pattern = new ArrayList<>();
    ConcurrentHashMap<Long, Warehouse> warehouses = new ConcurrentHashMap<>();
    ConcurrentHashMap<Long, Set<Enum>> types = new ConcurrentHashMap<>();

    public void addReply(ReplyToAction odpowiedz) throws InterruptedException {
        if (!logActions.containsKey(odpowiedz.getStudentId())) {

            logActions.put(odpowiedz.getStudentId(), new ArrayList<>());

        }

        odpowiedz.setTimestamp(LocalDateTime.now());
        ArrayList<ReplyToAction> list = logActions.get(odpowiedz.getStudentId());
        list.add(odpowiedz);

        Thread.sleep(100);

    }

    public void addReplySequencer(ReplyToAction odpowiedz) throws InterruptedException {
        odpowiedz.setTimestamp(LocalDateTime.now());
        pattern.add(odpowiedz);

        Thread.sleep(100);

    }

    public void evaluate(long indeks) throws AssertionError {
        long czas = Duration.between(pattern.get(0).getTimestamp(), pattern.get(pattern.size() - 1).getTimestamp()).toSeconds();
        long liczbaAkceptacji = pattern.stream().filter(m -> Boolean.TRUE.equals(m.getZrealizowaneZamowienie())).count();
        long liczbaNieakceptacji = pattern.stream().filter(m ->
                m.getTyp().equals(ZamowieniaAkcje.POJEDYNCZE_ZAMOWIENIE)
                        || m.getTyp().equals(ZamowieniaAkcje.GRUPOWE_ZAMOWIENIE)
                        || m.getTyp().equals(ZamowieniaAkcje.REZERWACJA)
                        || m.getTyp().equals(ZamowieniaAkcje.ODBI??R_REZERWACJI) &&
                        !m.getZrealizowaneZamowienie()
        ).count();
        Optional<ReplyToAction> last = pattern.stream().filter(m -> m.getTyp().equals(SterowanieAkcja.ZAMKNIJ_SKLEP)).findFirst();
        EnumMap<Product, Long> stanMagazyn??w = last.get().getStanMagazyn??w();
        EnumMap<Product, Long> stanCen = last.get().getGrupaProdukt??w();

        List<ReplyToAction> cenyOdpowiedzi = pattern.stream().filter(m -> m.getTyp().equals(WycenaAkcje.PODAJ_CENE))
                .sorted(Comparator.comparing(ReplyToAction::getId)).collect(Collectors.toList());
        List<ReplyToAction> inwOdpowiedzi = pattern.stream().filter(m -> m.getTyp().equals(WydarzeniaAkcje.INWENTARYZACJA))
                .sorted(Comparator.comparing(ReplyToAction::getId)).collect(Collectors.toList());
        List<ReplyToAction> raportyOdpowiedzi = pattern.stream().filter(m -> m.getTyp().equals(WydarzeniaAkcje.RAPORT_SPRZEDA??Y))
                .sorted(Comparator.comparing(ReplyToAction::getId)).collect(Collectors.toList());

        long czasStudent = Duration.between(logActions.get(indeks).get(0).getTimestamp(), logActions.get(indeks).get(logActions.get(indeks).size() - 1).getTimestamp())
                .toSeconds();
        long liczbaAkceptacjiStudent = logActions.get(indeks).stream().filter(m -> Boolean.TRUE.equals(m.getZrealizowaneZamowienie())).count();
        long liczbaNieakceptacjiStudent = logActions.get(indeks).stream().filter(m ->
                m.getTyp().equals(ZamowieniaAkcje.POJEDYNCZE_ZAMOWIENIE)
                        || m.getTyp().equals(ZamowieniaAkcje.GRUPOWE_ZAMOWIENIE)
                        || m.getTyp().equals(ZamowieniaAkcje.REZERWACJA)
                        || m.getTyp().equals(ZamowieniaAkcje.ODBI??R_REZERWACJI) &&
                        !m.getZrealizowaneZamowienie()
        ).count();
        Optional<ReplyToAction> lastStudent = logActions.get(indeks).stream().filter(m -> m.getTyp().equals(SterowanieAkcja.ZAMKNIJ_SKLEP))
                .findFirst();
        EnumMap<Product, Long> stanMagazyn??wStudent = lastStudent.get().getStanMagazyn??w();
        EnumMap<Product, Long> stanCenStudent = lastStudent.get().getGrupaProdukt??w();

        List<ReplyToAction> cenyOdpowiedziStudent = logActions.get(indeks).stream().filter(m -> m.getTyp().equals(WycenaAkcje.PODAJ_CENE))
                .sorted(Comparator.comparing(ReplyToAction::getId)).collect(Collectors.toList());
        List<ReplyToAction> inwOdpowiedziStudent = logActions.get(indeks).stream().filter(m -> m.getTyp().equals(WydarzeniaAkcje.INWENTARYZACJA))
                .sorted(Comparator.comparing(ReplyToAction::getId)).collect(Collectors.toList());
        List<ReplyToAction> raportyOdpowiedziStudent = logActions.get(indeks).stream()
                .filter(m -> m.getTyp().equals(WydarzeniaAkcje.RAPORT_SPRZEDA??Y))
                .sorted(Comparator.comparing(ReplyToAction::getId)).collect(Collectors.toList());

        assertThat(czasStudent).as("Tw??j program nie dziala krocej").isLessThan(czas);
        assertThat(liczbaAkceptacjiStudent).as("Tw??j program zaakceptowal/odrzucil (nie)poprawne zakupy").isEqualTo(liczbaAkceptacji);
        assertThat(liczbaNieakceptacjiStudent).as("Tw??j program odrzucil/nie odrzucil niepoprawnych zakupow").isEqualTo(liczbaNieakceptacji);
        assertThat(stanMagazyn??wStudent).as("Stan magazyn??w na koniec sie nie zgadza").containsAllEntriesOf(stanMagazyn??w);
        assertThat(stanCenStudent).as("Stan cen na koniec sie nie zgadza").containsAllEntriesOf(stanCen);
        for (int i = 0; i < cenyOdpowiedziStudent.size(); i++){
            assertThat(cenyOdpowiedziStudent.get(i)).usingRecursiveComparison().ignoringFields("timestamp", "studentId").isEqualTo(cenyOdpowiedzi.get(i));
        }
        for (int i = 0; i < inwOdpowiedziStudent.size(); i++){
            assertThat(inwOdpowiedziStudent.get(i)).usingRecursiveComparison().ignoringFields("timestamp", "studentId").isEqualTo(inwOdpowiedzi.get(i));
        }
        for (int i = 0; i < raportyOdpowiedziStudent.size(); i++){
            assertThat(raportyOdpowiedziStudent.get(i)).usingRecursiveComparison().ignoringFields("timestamp", "studentId").isEqualTo(raportyOdpowiedzi.get(i));
        }
        log.warn("Je??eli nie ma bledow program zadzialal poprawnie");
    }
    public void clear() {
        pattern.clear();
        logActions.clear();
        actions.clear();
        warehouses.clear();
        types.clear();
    }
}
