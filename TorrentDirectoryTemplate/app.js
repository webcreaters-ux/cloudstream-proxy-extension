const torrents=[
{name:'Ubuntu 24.04 LTS Desktop',cat:'Linux',seeds:984,leech:42,date:'Today',size:'5.8 GB',user:'Ubuntu'},
{name:'Debian 13 Netinstall',cat:'Linux',seeds:731,leech:31,date:'Today',size:'769 MB',user:'Debian'},
{name:'Blender Open Movie: Big Buck Bunny',cat:'Movies',seeds:522,leech:18,date:'Yesterday',size:'276 MB',user:'Blender'},
{name:'LibreOffice 25.2',cat:'Apps',seeds:417,leech:12,date:'Yesterday',size:'345 MB',user:'LibreOffice'},
{name:'Fedora Workstation 42',cat:'Linux',seeds:386,leech:20,date:'2 days ago',size:'2.7 GB',user:'Fedora'},
{name:'Sintel — Creative Commons Film',cat:'Movies',seeds:302,leech:9,date:'2 days ago',size:'1.4 GB',user:'Wikimedia'},
{name:'Open Source Game Collection',cat:'Games',seeds:241,leech:16,date:'3 days ago',size:'1.1 GB',user:'OpenGames'},
{name:'Free Music Archive Collection',cat:'Music',seeds:198,leech:11,date:'4 days ago',size:'3.2 GB',user:'FMA'}
];
let category='All';
function filterTorrents(){render();}
function render(){const q=document.getElementById('search').value.toLowerCase();const sort=document.getElementById('sort').value;let list=torrents.filter(x=>(category==='All'||x.cat===category)&&x.name.toLowerCase().includes(q));list.sort((a,b)=>sort==='seeders'?b.seeds-a.seeds:sort==='size'?parseFloat(b.size)-parseFloat(a.size):a.name.localeCompare(b.name));document.getElementById('rows').innerHTML=list.map(x=>`<tr><td>${x.name}</td><td>${x.seeds}</td><td>${x.leech}</td><td>${x.date}</td><td>${x.size}</td><td>${x.user}</td></tr>`).join('')||'<tr><td colspan="6">No matching legal content found.</td></tr>'}
document.querySelectorAll('.categories button').forEach(b=>b.addEventListener('click',()=>{document.querySelectorAll('.categories button').forEach(x=>x.classList.remove('active'));b.classList.add('active');category=b.dataset.cat;render()}));
document.getElementById('search').addEventListener('input',render);document.getElementById('sort').addEventListener('change',render);render();
