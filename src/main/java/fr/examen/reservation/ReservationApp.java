package fr.examen.reservation;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** Application de démonstration client-serveur de réservation de salles. */
public final class ReservationApp {
    static final int PORT = 5000;
    static final Path DATA = Paths.get("data");
    static final Path STORE = DATA.resolve("reservations.bin");
    static final Path LOG = DATA.resolve("logs.txt");
    static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private ReservationApp() {}

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA);
        if (args.length > 0 && args[0].equalsIgnoreCase("server")) new ReservationServer().start();
        else if (args.length > 0 && args[0].equalsIgnoreCase("client")) SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
        else {
            int answer = JOptionPane.showOptionDialog(null, "Choisissez le mode de lancement", "Réservations de salles",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new String[]{"Démarrer le serveur", "Ouvrir le client"}, "Ouvrir le client");
            if (answer == 0) new ReservationServer().start();
            else if (answer == 1) SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
        }
    }

    public static final class Request implements Serializable {
        private static final long serialVersionUID = 1L;
        public String action, email, password, role;
        public int id, userId, roomId;
        public String name, type, date, start, end, reason, status, query;
        public int capacity;
        public Request(String action) { this.action = action; }
    }
    public static final class Response implements Serializable {
        private static final long serialVersionUID = 1L;
        public boolean ok; public String message; public Object data;
        Response(boolean ok, String message, Object data) { this.ok=ok; this.message=message; this.data=data; }
        static Response yes(String m,Object d){return new Response(true,m,d);} static Response no(String m){return new Response(false,m,null);}
    }
    public static final class User implements Serializable {
        private static final long serialVersionUID=1L; int id; String name,email,password,role;
        User(int i,String n,String e,String p,String r){id=i;name=n;email=e;password=p;role=r;}
        Map<String,Object> map(){return Map.of("id",id,"name",name,"email",email,"role",role);}
    }
    public static final class Room implements Serializable {
        private static final long serialVersionUID=1L; int id,capacity; String name,type; boolean available=true;
        Room(int i,String n,String t,int c){id=i;name=n;type=t;capacity=c;}
        Map<String,Object> map(){return Map.of("id",id,"name",name,"type",type,"capacity",capacity,"available",available);}
    }
    public static final class Reservation implements Serializable {
        private static final long serialVersionUID=1L; int id,userId,roomId; String date,start,end,reason,status;
        Reservation(int i,int u,int r,String d,String s,String e,String m,String st){id=i;userId=u;roomId=r;date=d;start=s;end=e;reason=m;status=st;}
        Map<String,Object> map(){return Map.of("id",id,"userId",userId,"roomId",roomId,"date",date,"start",start,"end",end,"reason",reason,"status",status);}
    }
    static final class State implements Serializable {
        private static final long serialVersionUID=1L; int userSeq=1,roomSeq=1,resSeq=1;
        ArrayList<User> users=new ArrayList<>(); ArrayList<Room> rooms=new ArrayList<>(); ArrayList<Reservation> reservations=new ArrayList<>();
        static State seeded(){State s=new State(); s.users.add(new User(s.userSeq++,"Administrateur","admin@demo.ma","admin123","ADMIN"));s.users.add(new User(s.userSeq++,"Utilisateur","user@demo.ma","user123","USER"));s.rooms.add(new Room(s.roomSeq++,"Salle Atlas","Réunion",12));s.rooms.add(new Room(s.roomSeq++,"Salle Rif","Formation",24));s.rooms.add(new Room(s.roomSeq++,"Salle Oasis","Conférence",50));return s;}
    }

    /** Persistance locale atomique, protégée contre les collisions concurrentes côté serveur. */
    static final class Store {
        private State state;
        Store() { try { if(Files.exists(STORE)) try(ObjectInputStream in=new ObjectInputStream(Files.newInputStream(STORE))){state=(State)in.readObject();} else state=State.seeded(); save(); } catch(Exception e){state=State.seeded();try{save();}catch(Exception ignored){}} }
        synchronized <T>T use(Callable<T> action) throws Exception { T v=action.call(); save(); return v; }
        synchronized State state(){return state;}
        private void save() throws IOException {Files.createDirectories(DATA);Path tmp=DATA.resolve("reservations.tmp");try(ObjectOutputStream out=new ObjectOutputStream(Files.newOutputStream(tmp))){out.writeObject(state);}try{Files.move(tmp,STORE,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,STORE,StandardCopyOption.REPLACE_EXISTING);}}
    }

    static final class ReservationServer {
        private final Store store=new Store(); private final ExecutorService pool=Executors.newCachedThreadPool(); private volatile boolean running=true;
        void start() throws IOException {try(ServerSocket server=new ServerSocket(PORT)){System.out.println("Serveur démarré sur le port "+PORT);while(running){Socket s=server.accept();pool.execute(new ClientHandler(s,store));}}finally{pool.shutdown();}}
    }
    static final class ClientHandler implements Runnable {
        private final Socket socket; private final Store store;
        ClientHandler(Socket s,Store db){socket=s;store=db;}
        public void run(){try(Socket s=socket;ObjectOutputStream out=new ObjectOutputStream(s.getOutputStream());ObjectInputStream in=new ObjectInputStream(s.getInputStream())){out.flush();while(true){Object o=in.readObject();if(!(o instanceof Request))break;Response r;try{r=handle((Request)o);}catch(Exception e){r=Response.no(e.getMessage()==null?"Erreur serveur":e.getMessage());}out.writeObject(r);out.flush();out.reset();}}catch(EOFException ignored){}catch(Exception e){System.err.println("Client déconnecté: "+e.getMessage());}}
        private Response handle(Request q)throws Exception { synchronized(store) { return handleLocked(q); } }
        private Response handleLocked(Request q)throws Exception {
            State st=store.state();
            switch(q.action){
                case "login": {User u=st.users.stream().filter(x->x.email.equalsIgnoreCase(q.email)&&x.password.equals(q.password)).findFirst().orElse(null);if(u==null)return Response.no("Identifiants invalides");audit(u.name,"AUTHENTIFICATION","succès");return Response.yes("Connecté",u.map());}
                case "rooms": return Response.yes("",st.rooms.stream().map(Room::map).toList());
                case "reservations": return Response.yes("",st.reservations.stream().filter(x->q.userId<0||x.userId==q.userId).sorted(Comparator.comparing((Reservation x)->x.date).thenComparing(x->x.start)).map(Reservation::map).toList());
                case "stats": return Response.yes("",Map.of("rooms",st.rooms.size(),"reservations",st.reservations.size(),"confirmed",st.reservations.stream().filter(x->x.status.equals("CONFIRMÉE")).count()));
                case "addReservation": case "updateReservation": {
                    LocalDate d=LocalDate.parse(q.date);LocalTime a=LocalTime.parse(q.start),b=LocalTime.parse(q.end);if(!a.isBefore(b))return Response.no("L'heure de fin doit suivre l'heure de début.");
                    return store.use(()->{State x=store.state();if(x.rooms.stream().noneMatch(r->r.id==q.roomId))return Response.no("Salle introuvable.");
                        boolean clash=x.reservations.stream().anyMatch(r->r.roomId==q.roomId&&r.date.equals(d.toString())&&r.id!=q.id&&!r.status.equals("ANNULÉE")&&a.isBefore(LocalTime.parse(r.end))&&LocalTime.parse(r.start).isBefore(b));if(clash)return Response.no("Indisponible : cette salle est déjà réservée sur ce créneau.");
                        if(q.action.equals("addReservation")){int id=x.resSeq++;x.reservations.add(new Reservation(id,q.userId,q.roomId,d.toString(),a.toString(),b.toString(),safe(q.reason),"CONFIRMÉE"));}
                        else {Reservation r=x.reservations.stream().filter(z->z.id==q.id&&(z.userId==q.userId||"ADMIN".equals(q.role))).findFirst().orElse(null);if(r==null)return Response.no("Réservation introuvable ou accès refusé.");r.roomId=q.roomId;r.date=d.toString();r.start=a.toString();r.end=b.toString();r.reason=safe(q.reason);}
                        audit(q.email,q.action,"succès");return Response.yes("Réservation enregistrée.",null);});
                }
                case "deleteReservation": return store.use(()->{Reservation target=st.reservations.stream().filter(r->r.id==q.id&&(r.userId==q.userId||"ADMIN".equals(q.role))).findFirst().orElse(null);boolean ok=target!=null;if(ok)target.status="ANNULÉE";audit(q.email,"ANNULATION",ok?"succès":"introuvable/refusée");return ok?Response.yes("Réservation annulée.",null):Response.no("Réservation introuvable ou accès refusé.");});
                case "addRoom": return admin(q,()->{State x=store.state();x.rooms.add(new Room(x.roomSeq++,safe(q.name),safe(q.type),q.capacity));});
                case "updateRoom": return admin(q,()->{Room r=st.rooms.stream().filter(z->z.id==q.id).findFirst().orElseThrow();r.name=safe(q.name);r.type=safe(q.type);r.capacity=q.capacity;});
                case "deleteRoom": return admin(q,()->{if(st.reservations.stream().anyMatch(r->r.roomId==q.id&&!r.status.equals("ANNULÉE")))throw new IllegalStateException("Annulez d'abord les réservations actives de cette salle.");st.rooms.removeIf(r->r.id==q.id);});
                case "exportCsv": {StringBuilder csv=new StringBuilder("id,utilisateur,salle,date,début,fin,motif,statut\n");for(Reservation r:st.reservations){String un=st.users.stream().filter(u->u.id==r.userId).map(u->u.name).findFirst().orElse("");String rn=st.rooms.stream().filter(z->z.id==r.roomId).map(z->z.name).findFirst().orElse("");csv.append(r.id).append(',').append(csv(un)).append(',').append(csv(rn)).append(',').append(r.date).append(',').append(r.start).append(',').append(r.end).append(',').append(csv(r.reason)).append(',').append(r.status).append('\n');}Path p=DATA.resolve("reservations.csv");Files.writeString(p,csv,StandardCharsets.UTF_8);audit(q.email,"EXPORT CSV","succès");return Response.yes("Export créé : "+p.toAbsolutePath(),null);}
                case "backup": try(ObjectOutputStream o=new ObjectOutputStream(Files.newOutputStream(DATA.resolve("reservations-backup.bin")))){o.writeObject(st);return Response.yes("Sauvegarde binaire créée.",null);}
                case "restore": return admin(q,()->{Path p=DATA.resolve("reservations-backup.bin");if(!Files.exists(p))throw new IllegalStateException("Aucune sauvegarde binaire à lire.");try(ObjectInputStream in=new ObjectInputStream(Files.newInputStream(p))){State saved=(State)in.readObject();st.userSeq=saved.userSeq;st.roomSeq=saved.roomSeq;st.resSeq=saved.resSeq;st.users=saved.users;st.rooms=saved.rooms;st.reservations=saved.reservations;}catch(IOException|ClassNotFoundException e){throw new IllegalStateException("Lecture de la sauvegarde impossible : "+e.getMessage());}});
                default:return Response.no("Opération inconnue.");
            }
        }
        private Response admin(Request q,Runnable op)throws Exception{if(!"ADMIN".equals(q.role))return Response.no("Droit administrateur requis.");return store.use(()->{op.run();audit(q.email,q.action,"succès");return Response.yes("Opération effectuée.",null);});}
    }
    static String safe(String s){return s==null?"":s.trim();}
    static String csv(String s){return "\""+safe(s).replace("\"","\"\"")+"\"";}
    static synchronized void audit(String user,String op,String result){try{Files.createDirectories(DATA);Files.writeString(LOG,LocalDateTime.now()+" | "+user+" | "+op+" | "+result+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException e){System.err.println("Journal inaccessible: "+e.getMessage());}}

    static final class ClientConnection implements Closeable {
        private Socket socket;private ObjectOutputStream out;private ObjectInputStream in;
        ClientConnection()throws IOException{socket=new Socket();socket.connect(new InetSocketAddress("127.0.0.1",PORT),2500);socket.setSoTimeout(10000);out=new ObjectOutputStream(socket.getOutputStream());out.flush();in=new ObjectInputStream(socket.getInputStream());}
        synchronized Response call(Request q)throws Exception{out.writeObject(q);out.flush();out.reset();return (Response)in.readObject();}
        public void close(){try{if(socket!=null)socket.close();}catch(IOException ignored){}}
    }

    static final class LoginFrame extends JFrame {
        final JTextField email=new JTextField("admin@demo.ma",22);final JPasswordField password=new JPasswordField("admin123",22);
        LoginFrame(){super("Réservation des salles — Connexion");setDefaultCloseOperation(EXIT_ON_CLOSE);setSize(390,220);setLocationRelativeTo(null);JPanel p=new JPanel(new GridLayout(3,2,8,8));p.setBorder(BorderFactory.createEmptyBorder(24,24,24,24));p.add(new JLabel("E-mail"));p.add(email);p.add(new JLabel("Mot de passe"));p.add(password);JButton b=new JButton("Se connecter");p.add(new JLabel());p.add(b);add(p);b.addActionListener(e->login());getRootPane().setDefaultButton(b);}
        void login(){Request q=new Request("login");q.email=email.getText();q.password=new String(password.getPassword());async(q,r->{if(!r.ok){error(r.message);return;}Map<?,?> u=(Map<?,?>)r.data;dispose();new Dashboard((int)u.get("id"),(String)u.get("name"),(String)u.get("email"),(String)u.get("role")).setVisible(true);});}
    }
    interface Done{void accept(Response r);}
    static void async(Request q,Done done){new SwingWorker<Response,Void>(){protected Response doInBackground()throws Exception{try(ClientConnection c=new ClientConnection()){return c.call(q);}}protected void done(){try{done.accept(get());}catch(Exception e){error("Connexion au serveur impossible : "+(e.getCause()==null?e.getMessage():e.getCause().getMessage())+"\nDémarrez d'abord le serveur.");}}}.execute();}
    static void error(String s){JOptionPane.showMessageDialog(null,s,"Information",JOptionPane.WARNING_MESSAGE);}
    static Request req(String action,int uid,String email,String role){Request q=new Request(action);q.userId=uid;q.email=email;q.role=role;return q;}

    static final class Dashboard extends JFrame {
        final int uid;final String user,email,role;final DefaultTableModel reservations=new DefaultTableModel(new Object[]{"ID","Salle ID","Date","Début","Fin","Motif","Statut"},0){public boolean isCellEditable(int r,int c){return false;}};
        final DefaultTableModel rooms=new DefaultTableModel(new Object[]{"ID","Nom","Type","Capacité","Disponible"},0){public boolean isCellEditable(int r,int c){return false;}};
        final JTable rt=new JTable(reservations),roomt=new JTable(rooms);final JLabel summary=new JLabel("Chargement…");
        Dashboard(int uid,String user,String email,String role){super("Gestion des réservations — "+user);this.uid=uid;this.user=user;this.email=email;this.role=role;setDefaultCloseOperation(EXIT_ON_CLOSE);setSize(900,570);setLocationRelativeTo(null);
            JPanel top=new JPanel(new BorderLayout());top.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));top.add(new JLabel("Bonjour "+user+" ("+role+")"),BorderLayout.WEST);top.add(summary,BorderLayout.EAST);add(top,BorderLayout.NORTH);
            JTabbedPane tabs=new JTabbedPane();tabs.add("Réservations",reservationPanel());tabs.add("Salles",roomPanel());if(role.equals("ADMIN"))tabs.add("Administration des salles",adminPanel());add(tabs);refresh();}
        JPanel reservationPanel(){JPanel p=new JPanel(new BorderLayout(8,8));p.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));p.add(new JScrollPane(rt));JPanel buttons=new JPanel();JButton add=new JButton("Ajouter"),edit=new JButton("Modifier"),del=new JButton("Annuler"),refresh=new JButton("Actualiser"),csv=new JButton("Exporter CSV"),backup=new JButton("Sauvegarde binaire"),restore=new JButton("Lire la sauvegarde");for(JButton b:new JButton[]{add,edit,del,refresh,csv,backup,restore})buttons.add(b);p.add(buttons,BorderLayout.SOUTH);add.addActionListener(e->editReservation(null));edit.addActionListener(e->{int row=rt.getSelectedRow();if(row>=0)editReservation((int)reservations.getValueAt(row,0));});del.addActionListener(e->deleteReservation());refresh.addActionListener(e->refresh());csv.addActionListener(e->export("exportCsv"));backup.addActionListener(e->export("backup"));restore.addActionListener(e->{if(role.equals("ADMIN")&&JOptionPane.showConfirmDialog(this,"Remplacer les données actuelles par la sauvegarde?","Confirmer",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION){export("restore");refresh();}});return p;}
        JPanel roomPanel(){JPanel p=new JPanel(new BorderLayout());p.add(new JScrollPane(roomt));JButton refresh=new JButton("Actualiser");refresh.addActionListener(e->refresh());p.add(refresh,BorderLayout.SOUTH);return p;}
        JPanel adminPanel(){JPanel p=new JPanel(new BorderLayout());p.add(new JScrollPane(roomt));JPanel b=new JPanel();JButton add=new JButton("Ajouter salle"),edit=new JButton("Modifier salle"),del=new JButton("Supprimer salle");b.add(add);b.add(edit);b.add(del);p.add(b,BorderLayout.SOUTH);add.addActionListener(e->editRoom(null));edit.addActionListener(e->{int x=roomt.getSelectedRow();if(x>=0)editRoom((int)rooms.getValueAt(x,0));});del.addActionListener(e->{int x=roomt.getSelectedRow();if(x>=0){Request q=req("deleteRoom",uid,email,role);q.id=(int)rooms.getValueAt(x,0);async(q,r->{info(r);refresh();});}});return p;}
        void editReservation(Integer id){JTextField room=new JTextField(),date=new JTextField(LocalDate.now().plusDays(1).toString()),start=new JTextField("09:00"),end=new JTextField("10:00"),reason=new JTextField();if(id!=null){for(int r=0;r<reservations.getRowCount();r++)if((int)reservations.getValueAt(r,0)==id){room.setText(""+reservations.getValueAt(r,1));date.setText(""+reservations.getValueAt(r,2));start.setText(""+reservations.getValueAt(r,3));end.setText(""+reservations.getValueAt(r,4));reason.setText(""+reservations.getValueAt(r,5));}}
            JPanel f=new JPanel(new GridLayout(5,2,6,6));f.add(new JLabel("ID de la salle"));f.add(room);f.add(new JLabel("Date (AAAA-MM-JJ)"));f.add(date);f.add(new JLabel("Heure début (HH:mm)"));f.add(start);f.add(new JLabel("Heure fin (HH:mm)"));f.add(end);f.add(new JLabel("Motif"));f.add(reason);if(JOptionPane.showConfirmDialog(this,f,id==null?"Nouvelle réservation":"Modifier la réservation",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{Request q=req(id==null?"addReservation":"updateReservation",uid,email,role);q.id=id==null?0:id;q.roomId=Integer.parseInt(room.getText());q.date=date.getText();q.start=start.getText();q.end=end.getText();q.reason=reason.getText();async(q,r->{info(r);refresh();});}catch(Exception e){error("Saisissez un identifiant de salle numérique.");}}
        void deleteReservation(){int row=rt.getSelectedRow();if(row<0)return;Request q=req("deleteReservation",uid,email,role);q.id=(int)reservations.getValueAt(row,0);async(q,r->{info(r);refresh();});}
        void editRoom(Integer id){JTextField name=new JTextField(),type=new JTextField("Réunion"),capacity=new JTextField("10");if(id!=null)for(int r=0;r<rooms.getRowCount();r++)if((int)rooms.getValueAt(r,0)==id){name.setText(""+rooms.getValueAt(r,1));type.setText(""+rooms.getValueAt(r,2));capacity.setText(""+rooms.getValueAt(r,3));}JPanel f=new JPanel(new GridLayout(3,2));f.add(new JLabel("Nom"));f.add(name);f.add(new JLabel("Type"));f.add(type);f.add(new JLabel("Capacité"));f.add(capacity);if(JOptionPane.showConfirmDialog(this,f,"Salle",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{Request q=req(id==null?"addRoom":"updateRoom",uid,email,role);q.id=id==null?0:id;q.name=name.getText();q.type=type.getText();q.capacity=Integer.parseInt(capacity.getText());async(q,r->{info(r);refresh();});}catch(Exception e){error("Capacité invalide.");}}
        void export(String action){async(req(action,uid,email,role),this::info);}void info(Response r){JOptionPane.showMessageDialog(this,r.message,r.ok?"Terminé":"Erreur",r.ok?JOptionPane.INFORMATION_MESSAGE:JOptionPane.WARNING_MESSAGE);}
        void refresh(){Request q=req("reservations",role.equals("ADMIN")?-1:uid,email,role);async(q,r->{if(!r.ok)return;reservations.setRowCount(0);for(Object o:(List<?>)r.data){Map<?,?> m=(Map<?,?>)o;reservations.addRow(new Object[]{m.get("id"),m.get("roomId"),m.get("date"),m.get("start"),m.get("end"),m.get("reason"),m.get("status")});}});async(req("rooms",uid,email,role),r->{if(r.ok){rooms.setRowCount(0);for(Object o:(List<?>)r.data){Map<?,?> m=(Map<?,?>)o;rooms.addRow(new Object[]{m.get("id"),m.get("name"),m.get("type"),m.get("capacity"),m.get("available")});}}});async(req("stats",uid,email,role),r->{if(r.ok){Map<?,?> m=(Map<?,?>)r.data;summary.setText("Salles : "+m.get("rooms")+"   |   Réservations : "+m.get("reservations")+"   |   Confirmées : "+m.get("confirmed"));}});}
    }
}
