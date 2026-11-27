PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS Utilisateur (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nom TEXT NOT NULL,
  email TEXT NOT NULL UNIQUE,
  motDePasse TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('ADMIN','USER'))
);
CREATE TABLE IF NOT EXISTS Salle (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nom TEXT NOT NULL,
  type TEXT NOT NULL,
  capacite INTEGER NOT NULL CHECK (capacite > 0),
  disponible INTEGER NOT NULL DEFAULT 1 CHECK (disponible IN (0,1))
);
CREATE TABLE IF NOT EXISTS Reservation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  idUtilisateur INTEGER NOT NULL REFERENCES Utilisateur(id),
  idSalle INTEGER NOT NULL REFERENCES Salle(id),
  date TEXT NOT NULL,
  heureDebut TEXT NOT NULL,
  heureFin TEXT NOT NULL,
  motif TEXT NOT NULL,
  statut TEXT NOT NULL DEFAULT 'CONFIRMÉE'
    CHECK (statut IN ('CONFIRMÉE','ANNULÉE')),
  CHECK (heureDebut < heureFin)
);
CREATE INDEX IF NOT EXISTS idx_reservation_salle_date ON Reservation(idSalle,date);
CREATE TRIGGER IF NOT EXISTS reservation_pas_de_chevauchement
BEFORE INSERT ON Reservation WHEN NEW.statut <> 'ANNULÉE'
BEGIN
 SELECT CASE WHEN EXISTS (
   SELECT 1 FROM Reservation r
   WHERE r.idSalle=NEW.idSalle AND r.date=NEW.date AND r.statut<>'ANNULÉE'
     AND NEW.heureDebut < r.heureFin AND r.heureDebut < NEW.heureFin
 ) THEN RAISE(ABORT,'Salle indisponible sur ce créneau') END;
END;
CREATE TRIGGER IF NOT EXISTS reservation_update_pas_de_chevauchement
BEFORE UPDATE ON Reservation WHEN NEW.statut <> 'ANNULÉE'
BEGIN
 SELECT CASE WHEN EXISTS (
   SELECT 1 FROM Reservation r
   WHERE r.idSalle=NEW.idSalle AND r.date=NEW.date AND r.id<>NEW.id AND r.statut<>'ANNULÉE'
     AND NEW.heureDebut < r.heureFin AND r.heureDebut < NEW.heureFin
 ) THEN RAISE(ABORT,'Salle indisponible sur ce créneau') END;
END;
INSERT OR IGNORE INTO Utilisateur(nom,email,motDePasse,role) VALUES
 ('Administrateur','admin@demo.ma','admin123','ADMIN'),
 ('Utilisateur','user@demo.ma','user123','USER');
INSERT OR IGNORE INTO Salle(id,nom,type,capacite,disponible) VALUES
 (1,'Salle Atlas','Réunion',12,1), (2,'Salle Rif','Formation',24,1), (3,'Salle Oasis','Conférence',50,1);
